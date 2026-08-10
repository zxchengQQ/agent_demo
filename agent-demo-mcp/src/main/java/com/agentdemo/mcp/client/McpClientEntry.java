package com.agentdemo.mcp.client;

import com.agentdemo.mcp.entity.McpServer;
import com.agentdemo.mcp.entity.McpServerStatus;
import com.agentdemo.mcp.entity.McpToolInfo;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP 客户端聚合对象
 * <p>
 * 业务含义：将单个 MCP Server 的所有运行时资源（McpClient、Transport、状态、工具列表）
 * 聚合为一个"档案袋"，便于统一管理生命周期与资源释放。
 * </p>
 * <p>
 * 设计原则：
 * 1. 资源安全：close() 方法保证 McpClient 资源一定被释放，即使抛异常也仅记录日志不向上传播
 * 2. 状态可观测：通过 status 字段反映当前 Server 的运行状态（CONNECTED/DISCONNECTED/ERROR/DISABLED）
 * 3. 工具列表快照：tools 字段记录该 Server 提供的所有工具元数据，便于删除时精确注销
 * </p>
 */
@Slf4j
@Getter
@Setter
public class McpClientEntry {

    /** Server 业务实体（含配置信息） */
    private final McpServer server;

    /** LangChain4j MCP 客户端实例（连接成功后由 DefaultMcpClient.Builder 创建） */
    private final McpClient mcpClient;

    /** MCP 传输对象（StdioMcpTransport 或 HttpSseMcpTransport） */
    private final McpTransport transport;

    /** 当前状态（CONNECTED/DISCONNECTED/ERROR/DISABLED） */
    private McpServerStatus status;

    /** 最近一次错误信息（连接失败、断线原因等，用于 REST API 状态查询） */
    private String lastError;

    /** 该 Server 提供的工具元数据列表（连接成功后由 listTools 拉取填充） */
    private List<McpToolInfo> tools = new ArrayList<>();

    public McpClientEntry(McpServer server, McpClient mcpClient, McpTransport transport) {
        this.server = server;
        this.mcpClient = mcpClient;
        this.transport = transport;
    }

    /**
     * 安全释放 McpClient 资源
     * <p>
     * 业务含义：删除 Server 或重连前必须调用，保证子进程退出或 HTTP 连接关闭。
     * 异常隔离：mcpClient.close() 抛 IOException 时仅记录 WARN 日志，不向上抛出，
     * 避免资源清理失败阻塞主流程（如应用关闭、Server 删除）。
     * </p>
     */
    public void close() {
        if (mcpClient == null) {
            return;
        }
        try {
            mcpClient.close();
        } catch (Exception e) {
            // 资源释放失败不阻塞主流程：记录日志后继续，避免影响 Server 删除或应用关闭
            log.warn("关闭 McpClient 失败, server={}, 错误: {}", server.getName(), e.getMessage());
        }
    }

    /**
     * 获取 Transport 包装器（CR-001）
     * <p>
     * 业务含义：McpToolExecutor 在捕获 Unsupported content type 异常时，
     * 通过此方法获取 McpTransportWrapper，进而获取缓存的原始 JSON-RPC 响应，
     * 从中提取图片 URL/base64 信息以 Markdown 格式返回给 LLM。
     * </p>
     *
     * @return McpTransportWrapper 实例，transport 非 Wrapper 类型时返回 null
     */
    public McpTransportWrapper getTransportWrapper() {
        if (transport instanceof McpTransportWrapper wrapper) {
            return wrapper;
        }
        return null;
    }
}
