package com.agentdemo.mcp.tool;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.mcp.client.McpClientEntry;
import com.agentdemo.mcp.client.McpClientRegistry;
import com.agentdemo.mcp.client.McpTransportWrapper;
import com.agentdemo.mcp.entity.McpServerStatus;
import com.agentdemo.observability.NoopTraceCollector;
import com.agentdemo.observability.TraceCollector;
import com.agentdemo.tools.sanitize.SanitizeContext;
import com.agentdemo.tools.sanitize.ToolOutputSanitizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.McpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * MCP 工具执行器
 * <p>
 * 业务含义：作为 ByteBuddy 动态生成工具方法的"中转站"，将 Agent 的工具调用请求
 * 翻译为 MCP 协议调用，并把 MCP Server 返回的结果翻译回来；同时统一处理超时、
 * 断线、连接异常等边界情况。
 * </p>
 * <p>
 * 设计原则：
 * 1. 依赖解耦：仅依赖 McpClientRegistry（纯存储层），不依赖 McpServerManager，避免循环依赖
 * 2. 异常统一：所有异常包装为 BusinessException(MCP_TOOL_CALL_FAILED)，便于上层统一处理
 * 3. 断线检测：检测到 IOException 时主动标记 entry 状态为 DISCONNECTED，便于后续状态查询
 * 4. 统一解析（CR-002）：所有内容类型从 McpTransportWrapper 缓存的原始 JSON-RPC 响应
 *    通过 McpContentParser 统一解析，不依赖 ToolExecutionResult 的内容提取路径
 * </p>
 * <p>
 * CR-001：MCP 协议层埋点（AC-N08）——execute 统一收口上报 McpCallEvent（原始 serverName/
 * toolName/argsJson/协议耗时/状态/断线标记），与 ToolExecutor 工具 span 双层平级（决策 9）；
 * 埋点异常静默降级（AC-E05）。
 * </p>
 */
@Slf4j
@Component
public class McpToolExecutor {

    private final McpClientRegistry clientRegistry;
    private final McpContentParser contentParser;
    private final ToolOutputSanitizer sanitizer;
    private final TraceCollector traceCollector;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 降级提示：Wrapper 缓存为空或解析失败时返回
     */
    private static final String FALLBACK_MESSAGE =
            "工具调用已成功执行，但返回结果包含图片、音频或其他非文本内容，当前环境无法直接展示。" +
            "请告知用户：1. 工具已执行成功 2. 返回内容为非文本格式 " +
            "3. 建议重新描述需求或使用其他工具获取文本信息。";

    public McpToolExecutor(McpClientRegistry clientRegistry, McpContentParser contentParser) {
        this(clientRegistry, contentParser, ToolOutputSanitizer.disabled(), new NoopTraceCollector());
    }

    /**
     * 兼容构造（测试场景：指定清洗器，埋点 Noop）
     */
    public McpToolExecutor(McpClientRegistry clientRegistry, McpContentParser contentParser,
                           ToolOutputSanitizer sanitizer) {
        this(clientRegistry, contentParser, sanitizer, new NoopTraceCollector());
    }

    /**
     * 真实构造（Spring 注入）：清洗链路生效 + 追踪埋点挂载。@Autowired 明确指定 Spring
     * 在多构造器下使用本构造注入依赖（否则 Spring 无无参构造 + 多构造器无法创建 Bean，启动失败）。
     */
    @Autowired
    public McpToolExecutor(McpClientRegistry clientRegistry, McpContentParser contentParser,
                           ToolOutputSanitizer sanitizer, TraceCollector traceCollector) {
        this.clientRegistry = clientRegistry;
        this.contentParser = contentParser;
        this.sanitizer = sanitizer;
        this.traceCollector = traceCollector != null ? traceCollector : new NoopTraceCollector();
    }

    /**
     * 执行 MCP 工具调用
     * <p>
     * 业务含义：ByteBuddy 生成的代理方法委托到此，统一处理工具调用与异常。
     * </p>
     *
     * @param serverName MCP Server 名称
     * @param toolName   工具原始名称（不带 mcp_ 前缀）
     * @param argsJson   参数 JSON 字符串（LLM 通过 @Tool 描述理解格式后构造）
     * @return 工具执行结果文本
     * @throws BusinessException Server 不存在/状态异常/参数格式错误/调用失败时抛出
     */
    public String execute(String serverName, String toolName, String argsJson) {
        long startNanos = System.nanoTime();
        boolean[] disconnected = {false};
        try {
            String result = doExecute(serverName, toolName, argsJson, disconnected);
            recordMcp(serverName, toolName, argsJson, startNanos, true, disconnected[0], null);
            return result;
        } catch (BusinessException e) {
            // 业务含义：失败路径也上报（AC-N08 失败不丢记录），随后原样上抛给调用方（AC-E05）
            recordMcp(serverName, toolName, argsJson, startNanos, false, disconnected[0], e.getMessage());
            throw e;
        }
    }

    /**
     * MCP 工具调用核心逻辑（原 execute 主体，CR-001 抽取以统一埋点收口）
     */
    private String doExecute(String serverName, String toolName, String argsJson, boolean[] disconnected) {
        // 1. 查找 entry
        McpClientEntry entry = clientRegistry.get(serverName);
        if (entry == null) {
            throw new BusinessException(ErrorCode.MCP_TOOL_CALL_FAILED,
                    "MCP Server 不存在: " + serverName);
        }

        // 2. 校验状态
        if (entry.getStatus() != McpServerStatus.CONNECTED) {
            throw new BusinessException(ErrorCode.MCP_TOOL_CALL_FAILED,
                    "MCP Server " + serverName + " 当前状态为 " + entry.getStatus() + "，不可用");
        }

        // 3. 校验并规范化 argsJson
        String normalizedArgs = normalizeArgsJson(serverName, toolName, argsJson);

        // 4. 构造 ToolExecutionRequest
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .name(toolName)
                .arguments(normalizedArgs)
                .build();

        // 5. 调用 McpClient.executeTool
        // CR-002: executeTool 返回值被有意丢弃，仅用于触发 MCP 协议交换
        // 所有内容类型统一从 Wrapper 缓存的原始响应通过 McpContentParser 解析
        McpClient mcpClient = entry.getMcpClient();
        try {
            mcpClient.executeTool(request);
        } catch (RuntimeException e) {
            // 检测 IOException 包装：McpClient 内部 IO 异常会以 RuntimeException 形式抛出
            if (isIOExceptionCause(e)) {
                markDisconnected(entry, serverName, e.getMessage());
                disconnected[0] = true;
                throw new BusinessException(ErrorCode.MCP_TOOL_CALL_FAILED,
                        "MCP Server " + serverName + " 已断开: " + e.getMessage(), e);
            }
            // CR-002: Unsupported content type 视为预期行为（非文本内容的正常返回）
            // 不作为错误处理，继续到统一解析路径
            if (!isUnsupportedContentTypeException(e)) {
                throw new BusinessException(ErrorCode.MCP_TOOL_CALL_FAILED,
                        "MCP 工具调用失败 [" + serverName + "/" + toolName + "]: " + e.getMessage(), e);
            }
        }

        // 6. 统一从 Wrapper 缓存解析原始响应（CR-002）
        return parseFromWrapper(entry, serverName, toolName);
    }

    /**
     * MCP 协议层埋点（CR-001，AC-N08）
     * <p>
     * 业务含义：统一上报 McpCallEvent（原始 serverName/toolName/argsJson 与协议耗时）；
     * 埋点自身异常静默降级（AC-E05），不影响调用主流程。
     * </p>
     */
    private void recordMcp(String serverName, String toolName, String argsJson, long startNanos,
                           boolean success, boolean disconnected, String errorMessage) {
        try {
            traceCollector.recordMcp(new TraceCollector.McpCallEvent(
                    serverName, toolName, argsJson,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos),
                    success, disconnected, errorMessage));
        } catch (Exception e) {
            log.warn("LangSmith MCP 采集失败（降级跳过）: {}", e.getMessage());
        }
    }

    /**
     * 从 Wrapper 缓存的原始 JSON-RPC 响应统一解析（CR-002）
     * <p>
     * 业务含义：取代 CR-001 的 extractResultText + extractFromRawResponse 双重解析路径，
     * 所有内容类型（text/image/audio/resource/structuredContent/unknown）统一委托
     * McpContentParser 解析。解析结果（含降级提示）统一经 ToolOutputSanitizer 清洗包裹后返回，
     * 使 MCP Server（外部不可信数据源）的返回内容作为数据而非指令进入上下文（AC-S06）。
     * </p>
     *
     * @param entry      MCP 客户端聚合对象
     * @param serverName MCP Server 名称
     * @param toolName   工具原始名称
     * @return 清洗后的文本，或清洗后的降级提示
     */
    private String parseFromWrapper(McpClientEntry entry, String serverName, String toolName) {
        McpTransportWrapper wrapper = entry.getTransportWrapper();
        if (wrapper == null) {
            log.warn("parseFromWrapper: entry 无 McpTransportWrapper");
            return sanitizeMcpResult(FALLBACK_MESSAGE, serverName, toolName);
        }
        String rawResponse = wrapper.getLastRawResponse();
        wrapper.clearCachedResponse();
        if (rawResponse == null || rawResponse.isBlank()) {
            log.warn("parseFromWrapper: Wrapper 缓存为空");
            return sanitizeMcpResult(FALLBACK_MESSAGE, serverName, toolName);
        }
        String parsed = contentParser.parse(rawResponse);
        if (parsed != null) {
            return sanitizeMcpResult(parsed, serverName, toolName);
        }
        // 解析失败时回退到降级提示
        return sanitizeMcpResult(FALLBACK_MESSAGE, serverName, toolName);
    }

    /**
     * MCP 工具结果统一清洗（工具产出安全清洗）
     * <p>
     * 业务含义：MCP Server 返回值属外部不可信数据源，统一经清洗管道处理：
     * 可疑指令分级处置 + 字数限制 + "外部数据、非指令"边界声明（AC-S05/S06/T01）。
     * </p>
     */
    private String sanitizeMcpResult(String text, String serverName, String toolName) {
        return sanitizer.sanitize(text, SanitizeContext.builder()
                .toolName("mcp:" + serverName + "/" + toolName)
                .sourceDesc("MCP 工具返回内容")
                .htmlContent(false)
                .build());
    }

    /**
     * 检测异常是否为 LangChain4j ToolExecutionHelper 的 Unsupported content type 异常
     * <p>
     * 业务含义：MCP Server 返回非文本内容（image/audio 等）时，LangChain4j 内部
     * ToolExecutionHelper.extractResult() 会抛出此异常。这是预期行为，不是真正的错误。
     * </p>
     */
    private boolean isUnsupportedContentTypeException(RuntimeException e) {
        return e.getMessage() != null && e.getMessage().contains("Unsupported content type");
    }

    /**
     * 规范化 argsJson
     * <p>
     * 业务含义：null/空字符串视为 "{}"；非空字符串必须为合法 JSON。
     * </p>
     */
    private String normalizeArgsJson(String serverName, String toolName, String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return "{}";
        }
        try {
            // 校验 JSON 格式：解析为 Map 验证合法性
            objectMapper.readTree(argsJson);
            return argsJson;
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.MCP_TOOL_CALL_FAILED,
                    "MCP 工具参数 JSON 格式错误 [" + serverName + "/" + toolName + "]: " + e.getMessage(), e);
        }
    }

    /**
     * 检测异常链中是否包含 IOException
     */
    private boolean isIOExceptionCause(Throwable e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof IOException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 标记 entry 为 DISCONNECTED 状态
     * <p>
     * 业务含义：检测到 IO 异常时主动更新状态，便于 REST API 状态查询反映真实情况。
     * 注意：此处仅更新状态，不触发工具注销（由用户 reconnect 或 deleteServer 触发）。
     * </p>
     */
    private void markDisconnected(McpClientEntry entry, String serverName, String error) {
        entry.setStatus(McpServerStatus.DISCONNECTED);
        entry.setLastError("IO 异常导致断线: " + error);
        log.warn("MCP Server {} 检测到 IO 异常，已标记为 DISCONNECTED: {}", serverName, error);
    }
}
