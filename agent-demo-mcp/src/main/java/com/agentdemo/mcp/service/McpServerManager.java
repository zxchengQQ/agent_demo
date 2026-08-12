package com.agentdemo.mcp.service;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.mcp.client.McpClientEntry;
import com.agentdemo.mcp.client.McpClientRegistry;
import com.agentdemo.mcp.client.McpTransportFactory;
import com.agentdemo.mcp.config.McpProperties;
import com.agentdemo.mcp.entity.McpServer;
import com.agentdemo.mcp.entity.McpServerStatus;
import com.agentdemo.mcp.entity.McpToolInfo;
import com.agentdemo.mcp.tool.McpToolRegistrar;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP Server 核心管理服务
 * <p>
 * 业务含义：MCP 模块的"总管家"，负责 Server 的 CRUD、连接建立、状态机管理，
 * 协调传输工厂（创建 transport）、存储层（管理 entry）、工具注册器（注册/注销工具）。
 * </p>
 * <p>
 * 设计原则：
 * 1. 单一入口：所有 Server 生命周期操作（增删改查/连接/重连/断线）统一由此类处理
 * 2. 状态机管理：维护每个 Server 的 4 种状态流转（CONNECTED/DISCONNECTED/ERROR/DISABLED）
 * 3. 依赖解耦：通过 McpToolRegistrar 接口调用工具注册，避免与实现类循环依赖
 * </p>
 */
@Slf4j
@Service
public class McpServerManager {

    private final McpClientRegistry clientRegistry;
    private final McpTransportFactory transportFactory;
    private final McpToolRegistrar toolRegistrar;
    private final McpProperties mcpProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public McpServerManager(McpClientRegistry clientRegistry,
                           McpTransportFactory transportFactory,
                           McpToolRegistrar toolRegistrar,
                           McpProperties mcpProperties) {
        this.clientRegistry = clientRegistry;
        this.transportFactory = transportFactory;
        this.toolRegistrar = toolRegistrar;
        this.mcpProperties = mcpProperties;
    }

    /**
     * 建立 MCP Server 连接（核心方法，被 addServer/reconnect/静态加载复用）
     * <p>
     * 业务含义：把一个 MCP Server 的配置转化为可用的运行时资源（McpClient + 工具列表），
     * 并注册到 Registry 供后续工具调用。连接成功后工具立即可被 Agent 使用。
     * </p>
     * <p>
     * 处理步骤：创建 transport → 创建 McpClient → 拉取工具 → 存入 Registry → 注册工具 → 状态置 CONNECTED
     * </p>
     *
     * @param server MCP Server 业务实体（含配置信息）
     * @return 含工具列表与连接时间的 McpServer（同一引用）
     * @throws BusinessException transport 配置非法抛 PARAM_INVALID/MCP_TRANSPORT_UNSUPPORTED；
     *                           McpClient 创建或工具拉取失败抛 MCP_CONNECTION_FAILED
     */
    public McpServer connect(McpServer server) {
        String name = server.getName();
        log.info("开始连接 MCP Server: {}", name);

        // 1. 创建 transport（配置校验异常直接传播：PARAM_INVALID / MCP_TRANSPORT_UNSUPPORTED）
        McpTransport transport = transportFactory.createTransport(toServerConfig(server));

        // 2. 创建 McpClient（连接失败统一包装为 MCP_CONNECTION_FAILED）
        McpClient mcpClient;
        try {
            // 超时：单 Server toolTimeout 优先，否则用全局默认 defaultToolTimeout（AC-030）
            Duration timeout = server.getToolTimeout() != null
                    ? server.getToolTimeout()
                    : mcpProperties.getDefaultToolTimeout();
            mcpClient = createMcpClient(name, transport, timeout);
        } catch (Exception e) {
            // BUG-20260811：消息包含底层根因（如 CreateProcess 无法启动 npx），便于用户定位 stdio 配置问题
            throw new BusinessException(ErrorCode.MCP_CONNECTION_FAILED,
                    "连接 MCP Server 失败: " + name + "，原因: " + rootCauseMessage(e), e);
        }

        // 3. 拉取工具列表（失败也视为连接失败，需关闭已创建的 client 避免资源泄漏）
        List<ToolSpecification> specs;
        try {
            specs = mcpClient.listTools();
        } catch (Exception e) {
            closeQuietly(mcpClient, name);
            throw new BusinessException(ErrorCode.MCP_CONNECTION_FAILED,
                    "拉取工具列表失败: " + name + "，原因: " + rootCauseMessage(e), e);
        }

        // 4. 转换工具元数据并填充 server（构造注册名 mcp_{serverName}_{toolName}）
        List<McpToolInfo> toolInfos = convertToolSpecs(name, specs);
        server.setTools(toolInfos);
        server.setConnectTime(LocalDateTime.now());
        server.setStatus(McpServerStatus.CONNECTED);

        // 5. 创建 entry 并存入 registry（entry 状态置 CONNECTED，工具已可调用）
        McpClientEntry entry = new McpClientEntry(server, mcpClient, transport);
        entry.setStatus(McpServerStatus.CONNECTED);
        clientRegistry.put(name, entry);

        // 6. 注册工具到 ToolRegistry（触发 SimpleAgent delegate 重建，AC-005）
        toolRegistrar.registerTools(entry);

        log.info("MCP Server {} 连接成功, 工具数={}", name, toolInfos.size());
        return server;
    }

    /**
     * 动态添加 MCP Server（REST API 调用入口）
     * <p>
     * 业务含义：用户通过 POST /api/mcp/servers 动态添加新 Server，立即建立连接并注册工具。
     * 与静态加载的区别：必须反馈连接失败给调用方（抛异常），不静默跳过（BR-MCP-010）。
     * </p>
     */
    public McpServer addServer(McpServer server) {
        String name = server.getName();
        // 校验名称全局唯一性（含所有状态，AC-019, AC-029）
        if (clientRegistry.contains(name)) {
            throw new BusinessException(ErrorCode.MCP_SERVER_NAME_EXISTS,
                    "MCP Server 名称已存在: " + name);
        }
        // 委托 connect 完成连接（内部校验 transport 配置 + 创建 client + 拉取工具 + 注册）
        return connect(server);
    }

    /**
     * 删除 MCP Server
     * <p>
     * 业务含义：用户通过 DELETE /api/mcp/servers/{name} 删除 Server，
     * 注销其所有工具（Agent 无法再调用）、关闭连接（释放子进程/HTTP 连接）、从内存移除。
     * </p>
     */
    public void deleteServer(String name) {
        McpClientEntry entry = clientRegistry.get(name);
        if (entry == null) {
            throw new BusinessException(ErrorCode.MCP_SERVER_NOT_FOUND,
                    "MCP Server 不存在: " + name);
        }
        // 先注销工具（Agent 立即无法调用，AC-009）
        toolRegistrar.unregisterTools(entry);
        // 再关闭连接（entry.close 内部 try-catch，资源释放失败不阻塞，AC-027）
        entry.close();
        // 最后从 registry 移除
        clientRegistry.remove(name);
        log.info("MCP Server {} 已删除", name);
    }

    /**
     * 重连已断线/错误状态的 MCP Server
     * <p>
     * 业务含义：用户通过 POST /api/mcp/servers/{name}/reconnect 重新连接已断线的 Server，
     * 恢复工具可用性。已连接的 Server 不允许重连（AC-026）。
     * </p>
     */
    public McpServer reconnect(String name) {
        McpClientEntry entry = clientRegistry.get(name);
        if (entry == null) {
            throw new BusinessException(ErrorCode.MCP_SERVER_NOT_FOUND,
                    "MCP Server 不存在: " + name);
        }
        // 已连接的 Server 不允许重连（AC-026）
        if (entry.getStatus() == McpServerStatus.CONNECTED) {
            throw new BusinessException(ErrorCode.MCP_SERVER_ALREADY_CONNECTED,
                    "MCP Server 已连接，无需重连: " + name);
        }
        log.info("开始重连 MCP Server: {}, 当前状态={}", name, entry.getStatus());
        // 关闭旧连接（可能已断线，close 内部 try-catch 安全）
        entry.close();
        // 重新连接（connect 会 put 新 entry 覆盖旧的，AC-010）
        return connect(entry.getServer());
    }

    /**
     * 标记 Server 为断线状态（运行时检测到 IO 异常时调用）
     * <p>
     * 业务含义：McpToolExecutor 检测到 IO 异常时，通过此方法标记 Server 断线，
     * 并注销其工具（Agent 无法再调用，AC-016, AC-017）。
     * 注意：此方法不关闭连接（已断线），仅更新状态与注销工具。
     * </p>
     */
    public void markDisconnected(String name) {
        McpClientEntry entry = clientRegistry.get(name);
        if (entry == null) {
            throw new BusinessException(ErrorCode.MCP_SERVER_NOT_FOUND,
                    "MCP Server 不存在: " + name);
        }
        entry.setStatus(McpServerStatus.DISCONNECTED);
        entry.setLastError("检测到连接异常，已标记为断线");
        // 注销工具（Agent 无法再调用，触发 delegate 重建，AC-017）
        toolRegistrar.unregisterTools(entry);
        log.warn("MCP Server {} 已标记为 DISCONNECTED", name);
    }

    /**
     * 查询所有 Server 元数据列表（含静态和动态，AC-007）
     */
    public List<McpServer> list() {
        return clientRegistry.list().stream()
                .map(McpClientEntry::getServer)
                .collect(Collectors.toList());
    }

    /**
     * 获取单个 Server 元数据（不存在返回 null）
     */
    public McpServer getServer(String name) {
        McpClientEntry entry = clientRegistry.get(name);
        return entry == null ? null : entry.getServer();
    }

    /**
     * 查询指定 Server 暴露的工具元数据列表（AC-011）
     *
     * @throws BusinessException Server 不存在时抛 MCP_SERVER_NOT_FOUND（AC-020）
     */
    public List<McpToolInfo> listTools(String name) {
        McpClientEntry entry = clientRegistry.get(name);
        if (entry == null) {
            throw new BusinessException(ErrorCode.MCP_SERVER_NOT_FOUND,
                    "MCP Server 不存在: " + name);
        }
        return entry.getServer().getTools();
    }

    /**
     * 创建 McpClient 实例（protected 供测试 spy 覆盖，隔离真实连接）
     * <p>
     * 业务含义：封装 LangChain4j DefaultMcpClient.Builder 的构建细节，
     * 配置 key（标识）、toolExecutionTimeout（工具调用超时，AC-030）、transport（传输对象）。
     * </p>
     */
    protected McpClient createMcpClient(String name, McpTransport transport, Duration timeout) {
        return new DefaultMcpClient.Builder()
                .key(name)
                .toolExecutionTimeout(timeout)
                .initializationTimeout(Duration.ofSeconds(60)) // stdio 子进程首次启动可能较慢（如 npx 下载依赖）
                .transport(transport)
                .build();
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 提取异常链最深层根因的消息文本
     * <p>
     * 业务含义：连接失败时底层异常（如 IOException "CreateProcess error"）往往被多层包装，
     * 仅返回最外层消息无法定位问题。此方法递归提取最深层 cause 的消息并返回。
     * </p>
     */
    private String rootCauseMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        return msg == null || msg.isBlank() ? e.getMessage() : msg;
    }

    /**
     * 将 McpServer 业务实体转换为 McpProperties.ServerConfig
     * <p>
     * 业务含义：McpTransportFactory.createTransport 接收 ServerConfig，
     * McpServerManager 操作 McpServer，需在调用前转换。
     * </p>
     */
    private McpProperties.ServerConfig toServerConfig(McpServer server) {
        McpProperties.ServerConfig config = new McpProperties.ServerConfig();
        config.setName(server.getName());
        config.setTransport(server.getTransport());
        config.setEnabled(server.isEnabled());
        config.setCommand(server.getCommand());
        config.setArgs(server.getArgs());
        config.setEnv(server.getEnv());
        config.setUrl(server.getUrl());
        config.setHeaders(server.getHeaders());
        config.setToolTimeout(server.getToolTimeout());
        return config;
    }

    /**
     * 将 LangChain4j ToolSpecification 列表转换为 McpToolInfo 列表
     * <p>
     * 业务含义：MCP Server 返回的 ToolSpecification 是 LangChain4j 内部格式，
     * 转换为项目统一的 McpToolInfo（含注册名 mcp_{serverName}_{toolName}），
     * 便于后续 ByteBuddy 生成代理类与注销时按名匹配。
     * </p>
     */
    private List<McpToolInfo> convertToolSpecs(String serverName, List<ToolSpecification> specs) {
        List<McpToolInfo> toolInfos = new ArrayList<>();
        if (specs == null) {
            return toolInfos;
        }
        for (ToolSpecification spec : specs) {
            McpToolInfo info = new McpToolInfo();
            info.setOriginalName(spec.name());
            // 注册名遵循前缀规则 mcp_{serverName}_{toolName}（AC-031）
            info.setRegisteredName("mcp_" + serverName + "_" + spec.name());
            info.setDescription(spec.description());
            info.setParametersSchema(serializeParameters(spec));
            toolInfos.add(info);
        }
        return toolInfos;
    }

    /**
     * 序列化工具参数 schema 为 JSON 字符串
     * <p>
     * 业务含义：将 JsonObjectSchema 手动转换为标准 JSON Schema 字符串，供 LLM 理解参数格式。
     * 不能直接用 ObjectMapper 序列化 JsonObjectSchema，因为其 properties 类型为
     * Map&lt;String, JsonSchemaElement&gt;（多态接口），Jackson 无法自动序列化。
     * 序列化失败时降级为 "{}"，不阻塞连接流程。
     * </p>
     */
    private String serializeParameters(ToolSpecification spec) {
        try {
            JsonObjectSchema params = spec.parameters();
            if (params == null) {
                return "{}";
            }

            // 手动构造标准 JSON Schema 结构
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");

            // 提取 properties
            Map<String, JsonSchemaElement> rawProperties = params.properties();
            if (rawProperties != null && !rawProperties.isEmpty()) {
                Map<String, Object> properties = new LinkedHashMap<>();
                for (Map.Entry<String, JsonSchemaElement> entry : rawProperties.entrySet()) {
                    properties.put(entry.getKey(), convertSchemaElement(entry.getValue()));
                }
                schema.put("properties", properties);
            }

            // 提取 required
            List<String> required = params.required();
            if (required != null && !required.isEmpty()) {
                schema.put("required", required);
            }

            return objectMapper.writeValueAsString(schema);
        } catch (Exception e) {
            log.warn("序列化工具参数 schema 失败: tool={}, error={}", spec.name(), e.getMessage());
            return "{}";
        }
    }

    /**
     * 将 JsonSchemaElement 转换为可序列化的 Map
     * <p>
     * 业务含义：JsonSchemaElement 是多态接口，需根据具体类型提取 type/description 等字段。
     * </p>
     */
    private Map<String, Object> convertSchemaElement(JsonSchemaElement element) {
        Map<String, Object> result = new LinkedHashMap<>();

        if (element instanceof JsonStringSchema s) {
            result.put("type", "string");
            if (s.description() != null) {
                result.put("description", s.description());
            }
        } else if (element instanceof JsonIntegerSchema i) {
            result.put("type", "integer");
            if (i.description() != null) {
                result.put("description", i.description());
            }
        } else if (element instanceof JsonNumberSchema n) {
            result.put("type", "number");
            if (n.description() != null) {
                result.put("description", n.description());
            }
        } else if (element instanceof JsonBooleanSchema b) {
            result.put("type", "boolean");
            if (b.description() != null) {
                result.put("description", b.description());
            }
        } else if (element instanceof JsonObjectSchema o) {
            result.put("type", "object");
            if (o.description() != null) {
                result.put("description", o.description());
            }
            Map<String, JsonSchemaElement> rawProps = o.properties();
            if (rawProps != null && !rawProps.isEmpty()) {
                Map<String, Object> props = new LinkedHashMap<>();
                for (Map.Entry<String, JsonSchemaElement> entry : rawProps.entrySet()) {
                    props.put(entry.getKey(), convertSchemaElement(entry.getValue()));
                }
                result.put("properties", props);
            }
        } else {
            result.put("type", "any");
        }

        return result;
    }

    /**
     * 安全关闭 McpClient（资源释放失败仅记录日志，不向上抛出）
     */
    private void closeQuietly(McpClient client, String name) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (Exception e) {
            log.warn("关闭 McpClient 失败: server={}, 错误: {}", name, e.getMessage());
        }
    }
}
