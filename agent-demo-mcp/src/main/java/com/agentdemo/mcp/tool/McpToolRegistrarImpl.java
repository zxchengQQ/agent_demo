package com.agentdemo.mcp.tool;

import com.agentdemo.mcp.client.McpClientEntry;
import com.agentdemo.mcp.client.McpClientRegistry;
import com.agentdemo.mcp.config.McpProperties;
import com.agentdemo.mcp.entity.McpServer;
import com.agentdemo.mcp.entity.McpServerStatus;
import com.agentdemo.mcp.service.McpServerManager;
import com.agentdemo.tools.registry.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP 工具注册器实现 + 启动加载器
 * <p>
 * 业务含义：实现 {@link McpToolRegistrar} 接口（工具注册/注销）与 {@link ApplicationRunner}（启动加载静态 Server）。
 * 应用启动时按 application.yml 的 mcp.servers 配置连接所有静态 Server，把它们的工具注册到 ToolRegistry，
 * 使 Agent 通过 ReAct 循环自主调用外部 MCP 工具（AC-001）。
 * </p>
 * <p>
 * 循环依赖处理：McpServerManager 依赖 McpToolRegistrar 接口（注入此实现），
 * 本类依赖 McpServerManager（connect 静态 Server）。通过 @Lazy 注入 McpServerManager 打破循环。
 * </p>
 * <p>
 * 容错原则：单个静态 Server 加载失败时记录 ERROR 日志、跳过该 Server，不阻塞应用启动（AC-014）。
 * </p>
 */
@Slf4j
@Component
public class McpToolRegistrarImpl implements McpToolRegistrar, ApplicationRunner {

    private final McpProperties mcpProperties;
    private final McpServerManager serverManager;
    private final McpToolFactory toolFactory;
    private final ToolRegistry toolRegistry;
    private final McpClientRegistry clientRegistry;

    public McpToolRegistrarImpl(McpProperties mcpProperties,
                                @Lazy McpServerManager serverManager,
                                McpToolFactory toolFactory,
                                ToolRegistry toolRegistry,
                                McpClientRegistry clientRegistry) {
        this.mcpProperties = mcpProperties;
        this.serverManager = serverManager;
        this.toolFactory = toolFactory;
        this.toolRegistry = toolRegistry;
        this.clientRegistry = clientRegistry;
    }

    /**
     * 应用启动时加载静态配置的 MCP Server
     * <p>
     * 业务含义：应用启动后自动按 application.yml 的 mcp.servers 配置连接所有静态 Server，
     * 把它们的工具注册到 ToolRegistry，使 Agent 对话时能自主选择并调用 MCP 工具（AC-001）。
     * </p>
     * <p>
     * 容错：单个 Server 连接失败时记录 ERROR 日志、跳过，不阻塞应用启动（AC-014）。
     * 模块禁用：mcp.enabled=false 时跳过整个加载流程（AC-022）。
     * </p>
     */
    @Override
    public void run(ApplicationArguments args) {
        // 模块总开关：enabled=false 时跳过整个加载流程（AC-022）
        if (!mcpProperties.isEnabled()) {
            log.info("MCP 模块已禁用 (mcp.enabled=false)，跳过静态 Server 加载");
            return;
        }

        List<McpProperties.ServerConfig> servers = mcpProperties.getServers();
        if (servers == null || servers.isEmpty()) {
            log.info("MCP 静态 Server 配置为空，跳过加载");
            return;
        }

        log.info("开始加载 MCP 静态 Server，共 {} 个", servers.size());
        int successCount = 0;
        int failCount = 0;

        for (McpProperties.ServerConfig config : servers) {
            try {
                // 转换为 McpServer 业务实体
                McpServer server = toMcpServer(config);

                // enabled=false 时标记 DISABLED 并存入 registry，跳过连接（AC-032）
                if (!config.isEnabled()) {
                    log.info("MCP Server {} 已禁用 (enabled=false)，跳过连接", config.getName());
                    McpClientEntry disabledEntry = new McpClientEntry(server, null, null);
                    disabledEntry.setStatus(McpServerStatus.DISABLED);
                    clientRegistry.put(config.getName(), disabledEntry);
                    continue;
                }

                // 连接 Server（内部会拉取工具、存入 registry、注册工具）
                serverManager.connect(server);
                successCount++;
            } catch (Exception e) {
                // 单个 Server 加载失败不阻塞应用启动（AC-014）
                failCount++;
                log.error("MCP Server {} 加载失败，跳过该 Server: {}", config.getName(), e.getMessage(), e);
            }
        }

        log.info("MCP 静态 Server 加载完成: 成功={}, 失败={}", successCount, failCount);
    }

    /**
     * 将 entry 对应 Server 的所有工具注册到 ToolRegistry
     * <p>
     * 业务含义：McpServerManager.connect() 成功后调用此方法，
     * 通过 ByteBuddy 生成 @Tool 代理类并注册到 ToolRegistry，
     * 使 Agent 通过 Function Calling 调用 MCP 工具（AC-005）。
     * </p>
     */
    @Override
    public void registerTools(McpClientEntry entry) {
        McpServer server = entry.getServer();
        List<com.agentdemo.mcp.entity.McpToolInfo> toolInfos = server.getTools();
        if (toolInfos == null || toolInfos.isEmpty()) {
            log.info("MCP Server {} 无工具，跳过注册", server.getName());
            return;
        }

        // 通过 ByteBuddy 生成 @Tool 代理类
        List<Object> toolProxies = toolFactory.createTools(server.getName(), toolInfos);

        // 逐个注册到 ToolRegistry（触发 SimpleAgent delegate 重建）
        // 业务含义：传入 serverName 供 ToolRegistry 登记 mcp:{serverName} 标识，保证 id 精确
        for (Object proxy : toolProxies) {
            toolRegistry.register(proxy, server.getName());
        }
        log.info("MCP Server {} 工具注册完成，共 {} 个", server.getName(), toolProxies.size());
    }

    /**
     * 从 ToolRegistry 注销 entry 对应 Server 的所有工具
     * <p>
     * 业务含义：Server 断线（markDisconnected）或删除（deleteServer）时调用，
     * 按 registeredName 从 ToolRegistry 移除工具，Agent 立即无法再调用（AC-017）。
     * </p>
     */
    @Override
    public void unregisterTools(McpClientEntry entry) {
        McpServer server = entry.getServer();
        List<com.agentdemo.mcp.entity.McpToolInfo> toolInfos = server.getTools();
        if (toolInfos == null || toolInfos.isEmpty()) {
            return;
        }

        // 按 registeredName 逐个注销
        for (com.agentdemo.mcp.entity.McpToolInfo toolInfo : toolInfos) {
            toolRegistry.unregisterTool(toolInfo.getRegisteredName());
        }
        log.info("MCP Server {} 工具注销完成，共 {} 个", server.getName(), toolInfos.size());
    }

    /**
     * 将 ServerConfig 转换为 McpServer 业务实体
     * <p>
     * 业务含义：配置类 ServerConfig 与业务实体 McpServer 字段对齐，
     * 转换后由 McpServerManager.connect() 使用。
     * </p>
     */
    private McpServer toMcpServer(McpProperties.ServerConfig config) {
        McpServer server = new McpServer();
        server.setName(config.getName());
        server.setTransport(config.getTransport());
        server.setEnabled(config.isEnabled());
        server.setCommand(config.getCommand());
        server.setArgs(config.getArgs());
        server.setEnv(config.getEnv());
        server.setUrl(config.getUrl());
        server.setHeaders(config.getHeaders());
        server.setToolTimeout(config.getToolTimeout());
        return server;
    }
}
