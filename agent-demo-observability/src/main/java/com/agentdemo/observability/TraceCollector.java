package com.agentdemo.observability;

/**
 * 追踪采集器接口（langsmith-observability，Task-03）
 * <p>
 * 业务含义：LLM 调用与工具调用埋点的统一上报入口。埋点方（ModelFactory / ToolExecutor / 装饰器）
 * 只依赖本接口，不关心实现是 Noop（默认关闭）还是 OTLP 上报。
 * </p>
 * <p>
 * 事件字段设计对齐技术方案 §7.1 span 设计表；traceId/sessionId 不随事件传入，
 * 由实现方从 {@link TraceContextHolder} 读取（决策 5：ThreadLocal 上下文传播）。
 * </p>
 * <p>
 * 只读旁路设计（AC-S05）：本接口所有方法无返回值、无副作用，采集失败不得影响主流程
 * （实现方内部 try-catch，见技术方案 §3.3 失败表）。
 * </p>
 */
public interface TraceCollector {

    /**
     * 采集器是否启用。
     * <p>
     * 业务含义：供埋点装配方（ModelFactory）在默认关闭（AC-S02）时跳过 listener 挂载与
     * 装饰器包装，实现真正"零开销"——Noop 默认返回 false，Otlp 覆写为 true。
     * </p>
     *
     * @return true=启用，应挂载采集；false=未启用，跳过挂载
     */
    default boolean isEnabled() {
        return false;
    }

    /**
     * 开始一次请求级根 span（AC-T01：同一次用户消息的 LLM/工具 span 共享同一 trace）。
     * <p>
     * 在异步边界（Controller runAsync）内调用；实现方创建根 span 并使其 Context 成为
     * 当前线程的父上下文，后续 recordLlm/recordTool 生成的 span 自动成为其子 span。
     * 未启用（Noop）时为空操作。
     * </p>
     */
    void startRequest();

    /**
     * 结束请求级根 span（异步边界 finally 调用，与 {@link #startRequest()} 成对）。
     * 未启用（Noop）时为空操作。
     */
    void endRequest();

    /**
     * 标记一次 HITL 暂停，记录当前请求根 span 上下文供同键恢复续接（BUG 修复：单任务多轮人机交互共享同一 trace）。
     * <p>
     * 业务含义：HITL（askUser / tool_confirm / 工作流 WAITING_USER）暂停时，异步边界根 span
     * 即将随本次 HTTP 请求结束而关闭导出；恢复是新的 HTTP 请求，若不续接则 LangSmith 呈现
     * 一条完整链路 + 多条独立人机交互短链路（碎片化）。暂停点调用本方法保存根 span 上下文
     * （按 resumeKey 索引，chat=sessionId / 工作流=executionId），恢复轮经
     * {@link #resumeRequest(String)} 以其为父续接同一 trace。
     * </p>
     * <p>只读旁路（AC-S05）：无返回值，失败不影响主流程；未启用（Noop）时空操作。</p>
     *
     * @param resumeKey 续接键（chat HITL=sessionId，工作流 HITL=executionId）
     */
    default void markHITLPause(String resumeKey) {
        // 默认空操作（Noop/未启用实现无续接语义）
    }

    /**
     * 以 {@link #markHITLPause(String)} 记录的上下文为父，启动续接轮请求级根 span（BUG 修复）。
     * <p>
     * 业务含义：HITL 恢复轮（Controller hasPending 分支 / 工作流 hitlReply）调用；
     * 实现方以暂停轮根 span 为远程父上下文创建新根 span，使恢复轮 span 与原任务共享
     * 同一 traceId（LangSmith 单任务一条完整链路）。无对应暂停记录（如服务重启后）时
     * 回退为独立新 trace，不误续接。
     * </p>
     *
     * @param resumeKey 续接键（与 markHITLPause 同键；记录被消费即删，链式暂停由新一轮 mark 覆盖）
     */
    default void resumeRequest(String resumeKey) {
        // 默认回退独立新请求（无续接语义的实现）
        startRequest();
    }

    /**
     * 记录一次 LLM 调用
     *
     * @param event LLM 调用事件
     */
    void recordLlm(LlmCallEvent event);

    /**
     * 记录一次工具调用
     *
     * @param event 工具调用事件
     */
    void recordTool(ToolCallEvent event);

    /**
     * 记录一次 RAG 知识库检索（CR-001，AC-N05）
     *
     * @param event 检索事件
     */
    void recordRag(RagRetrievalEvent event);

    /**
     * 记录一次记忆滚动压缩（CR-001，AC-N06）
     *
     * @param event 压缩事件
     */
    void recordMemoryCompression(MemoryCompressionEvent event);

    /**
     * 记录一次工作流执行终态（CR-001，AC-N07）
     *
     * @param event 工作流级事件
     */
    void recordWorkflow(WorkflowExecutionEvent event);

    /**
     * 记录一个工作流步骤执行结果（CR-001，AC-N07）
     *
     * @param event 步骤级事件
     */
    void recordWorkflowStep(WorkflowStepEvent event);

    /**
     * 记录一次 MCP 协议层调用（CR-001，AC-N08）
     *
     * @param event MCP 调用事件
     */
    void recordMcp(McpCallEvent event);

    /**
     * 记录一次 Skill 激活（成功或被拒）（CR-001，AC-N09）
     *
     * @param event 激活事件
     */
    void recordSkillActivation(SkillActivationEvent event);

    /**
     * LLM 调用事件（技术方案 §7.1 LLM span 数据来源）
     *
     * @param modelName      实际使用的模型名（AC-N03，会话内如实反映，AC-M02）
     * @param promptText     输入消息文本（messages 序列化）
     * @param outputText     输出回复文本（失败时可为 null）
     * @param inputTokens    输入 Token 数（未知传 -1；Thinking 系取 onComplete 真实值）
     * @param outputTokens   输出 Token 数（未知传 -1）
     * @param durationMs     调用耗时（毫秒）
     * @param success        是否成功
     * @param errorMessage   失败原因（成功时 null）
     * @param finishReason   结束原因（stop / tool_calls，未知可 null）
     */
    record LlmCallEvent(
            String modelName,
            String promptText,
            String outputText,
            int inputTokens,
            int outputTokens,
            long durationMs,
            boolean success,
            String errorMessage,
            String finishReason) {
    }

    /**
     * 工具调用事件（技术方案 §7.1 工具 span 数据来源）
     *
     * @param toolName      工具名（AC-N04）
     * @param arguments     入参 JSON（LLM 生成）
     * @param result        出参文本（已过 sanitize 管道，失败时可为 null）
     * @param durationMs    耗时（毫秒）
     * @param success       是否成功（AC-T03：失败也记录，含异常信息）
     * @param errorMessage  失败原因（成功时 null）
     */
    record ToolCallEvent(
            String toolName,
            String arguments,
            String result,
            long durationMs,
            boolean success,
            String errorMessage) {
    }

    /**
     * RAG 检索事件（CR-001，技术方案 §7.1 检索 span 数据来源，AC-N05）
     *
     * @param kbId         知识库 ID（rag.kb.id）
     * @param query        查询词（rag.query）
     * @param kbName       知识库名称（rag.kb.name）
     * @param hitCount     命中块数（rag.hit_count）
     * @param topK         Top-N 配置（rag.top_k）
     * @param maxScore     最高相似度（rag.max_score，无命中时 0）
     * @param chunks       命中块列表文本（rag.chunks，已过 sanitize 管道，脱敏截断由 collector 承担）
     * @param durationMs   耗时（毫秒）
     * @param success      是否成功（知识库不存在/为空/无结果等提示文本场景为 true，异常为 false）
     * @param errorMessage 失败原因（成功时 null）
     */
    record RagRetrievalEvent(
            String kbId,
            String query,
            String kbName,
            int hitCount,
            int topK,
            double maxScore,
            String chunks,
            long durationMs,
            boolean success,
            String errorMessage) {
    }

    /**
     * 记忆压缩事件（CR-001，技术方案 §7.1 压缩 span 数据来源，AC-N06）
     *
     * @param messagesBefore 压缩前消息数（memory.messages_before）
     * @param messagesAfter  压缩后消息数（memory.messages_after）
     * @param compressedCount 本次压缩条数（memory.compressed_count）
     * @param window         窗口上限（memory.window）
     * @param summary        新摘要文本（memory.summary，脱敏截断由 collector 承担；降级时 null）
     * @param degraded       是否降级为 FIFO（memory.degraded）
     * @param durationMs     耗时（毫秒）
     */
    record MemoryCompressionEvent(
            int messagesBefore,
            int messagesAfter,
            int compressedCount,
            int window,
            String summary,
            boolean degraded,
            long durationMs) {
    }

    /**
     * 工作流执行终态事件（CR-001，技术方案 §7.1 工作流 span 数据来源，AC-N07/M03）
     *
     * @param executionId 执行 ID（workflow.execution_id，thread 聚合承载键=executionId）
     * @param templateId  模板 ID（workflow.template.id）
     * @param templateName 模板名（workflow.template.name，span 名用）
     * @param mode        编排模式（workflow.mode）
     * @param status      终态（workflow.status：COMPLETED/FAILED/TERMINATED/TIMEOUT/PAUSED/WAITING_USER）
     * @param durationMs  总耗时（workflow.duration_ms，历史总耗时）
     * @param finalResult 最终结果（workflow.final_result，失败/暂停时可为 null）
     */
    record WorkflowExecutionEvent(
            String executionId,
            String templateId,
            String templateName,
            String mode,
            String status,
            long durationMs,
            String finalResult) {
    }

    /**
     * 工作流步骤事件（CR-001，技术方案 §7.1 步骤 span 数据来源，AC-N07/M03）
     *
     * @param executionId 执行 ID（workflow.execution_id 关联）
     * @param agentName   步骤 Agent 名（span 名用）
     * @param index       步骤索引（workflow.step.index）
     * @param status      步骤状态（workflow.step.status）
     * @param durationMs  步骤耗时（workflow.step.duration_ms）
     * @param retryCount  重试次数（workflow.step.retry_count）
     * @param output      步骤输出（workflow.step.output，脱敏截断由 collector 承担）
     */
    record WorkflowStepEvent(
            String executionId,
            String agentName,
            int index,
            String status,
            long durationMs,
            int retryCount,
            String output) {
    }

    /**
     * MCP 协议层调用事件（CR-001，技术方案 §7.1 MCP span 数据来源，AC-N08）
     *
     * @param serverName   MCP Server 名（mcp.server）
     * @param toolName     原始工具名（mcp.tool，非 mcp_{server}_{tool} 拼接）
     * @param arguments    原始参数 JSON（mcp.arguments，脱敏截断由 collector 承担）
     * @param durationMs   协议耗时（mcp.duration_ms，毫秒）
     * @param success      是否成功
     * @param disconnected 是否因 IO 异常断线（mcp.disconnected）
     * @param errorMessage 失败原因（成功时 null）
     */
    record McpCallEvent(
            String serverName,
            String toolName,
            String arguments,
            long durationMs,
            boolean success,
            boolean disconnected,
            String errorMessage) {
    }

    /**
     * Skill 激活事件（CR-001，技术方案 §7.1 激活 span 数据来源，AC-N09）
     *
     * @param skillId        技能 ID（skill.id）
     * @param skillName      技能名（skill.name，span 名用，不可得时 null）
     * @param source         激活来源（skill.source：AUTO/MANUAL）
     * @param boundTools     绑定脚本工具名（skill.bound_tools，可空）
     * @param success        是否激活成功
     * @param rejectedReason 拒绝原因（skill.rejected_reason，成功时 null）
     */
    record SkillActivationEvent(
            String skillId,
            String skillName,
            String source,
            String boundTools,
            boolean success,
            String rejectedReason) {
    }
}
