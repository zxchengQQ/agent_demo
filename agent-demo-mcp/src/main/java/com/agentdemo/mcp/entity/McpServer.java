package com.agentdemo.mcp.entity;

import com.agentdemo.mcp.config.McpTransportType;
import lombok.Data;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP Server 业务实体
 * <p>
 * 业务含义：单个 MCP Server 的完整业务档案，含配置信息、运行时状态与工具列表。
 * 与 McpProperties.ServerConfig（配置类）的区别：McpServer 是业务实体类，额外携带运行时状态字段
 * （tools, connectTime, lastActiveTime），由 McpServerManager 在连接成功后构造。
 * </p>
 */
@Data
public class McpServer {

    /** Server 唯一标识（与知识库"名称全局唯一"风格一致，REST API 路径形如 /api/mcp/servers/{name}） */
    private String name;

    /** 传输方式：STDIO（本地子进程）或 SSE（HTTP+SSE 远程） */
    private McpTransportType transport;

    /** 是否启用（静态配置 enabled=false 时启动加载跳过该 Server） */
    private boolean enabled = true;

    // === stdio 传输方式专用字段 ===

    /** 启动 MCP Server 子进程的命令 */
    private String command;

    /** 命令行参数列表 */
    private List<String> args = new ArrayList<>();

    /** 子进程环境变量 */
    private Map<String, String> env = new HashMap<>();

    // === sse 传输方式专用字段 ===

    /** 远程 MCP Server 的 SSE 端点 URL */
    private String url;

    /** HTTP 请求头 */
    private Map<String, String> headers = new HashMap<>();

    // === 通用字段 ===

    /** 单 Server 工具调用超时（覆盖全局默认 default-tool-timeout） */
    private Duration toolTimeout;

    /** 该 Server 提供的工具元数据列表（连接成功后由 listTools 拉取填充） */
    private List<McpToolInfo> tools = new ArrayList<>();

    /** 首次连接成功时间 */
    private LocalDateTime connectTime;

    /** 最近活跃时间（用于断线检测与重连参考） */
    private LocalDateTime lastActiveTime;

    /** 当前运行时状态（由 McpServerManager 在连接/断线时设置，供 REST API 查询） */
    private McpServerStatus status;
}
