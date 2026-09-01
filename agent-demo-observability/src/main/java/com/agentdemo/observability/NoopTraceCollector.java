package com.agentdemo.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 空实现采集器（langsmith-observability，Task-03）
 * <p>
 * 业务含义：LangSmith 未启用（enabled=false 或未配 Key）时的默认采集器（AC-S02）。
 * 全部方法空实现，零网络、零状态、零开销——埋点代码无需分支判断，装配层决定注入
 * Noop 还是 OTLP 实现。
 * </p>
 */
public class NoopTraceCollector implements TraceCollector {

    private static final Logger log = LoggerFactory.getLogger(NoopTraceCollector.class);

    @Override
    public void startRequest() {
        // 业务含义：默认关闭时无根 span（AC-S02 零开销）
    }

    @Override
    public void endRequest() {
        // 业务含义：默认关闭时无根 span
    }

    @Override
    public void recordLlm(LlmCallEvent event) {
        // 业务含义：默认关闭时静默丢弃采集事件，仅 DEBUG 级别留痕便于排查（AC-S02）
        log.debug("LangSmith 未启用，跳过 LLM 采集: model={}", event.modelName());
    }

    @Override
    public void recordTool(ToolCallEvent event) {
        log.debug("LangSmith 未启用，跳过工具采集: tool={}", event.toolName());
    }

    @Override
    public void recordRag(RagRetrievalEvent event) {
        // 业务含义：默认关闭时静默丢弃五域采集事件（CR-001，AC-S02 零开销语义保持）
        log.debug("LangSmith 未启用，跳过 RAG 检索采集: kb={}, query={}", event.kbId(), event.query());
    }

    @Override
    public void recordMemoryCompression(MemoryCompressionEvent event) {
        log.debug("LangSmith 未启用，跳过记忆压缩采集: before={}, degraded={}",
                event.messagesBefore(), event.degraded());
    }

    @Override
    public void recordWorkflow(WorkflowExecutionEvent event) {
        log.debug("LangSmith 未启用，跳过工作流采集: executionId={}, status={}",
                event.executionId(), event.status());
    }

    @Override
    public void recordWorkflowStep(WorkflowStepEvent event) {
        log.debug("LangSmith 未启用，跳过工作流步骤采集: agent={}, status={}", event.agentName(), event.status());
    }

    @Override
    public void recordMcp(McpCallEvent event) {
        log.debug("LangSmith 未启用，跳过 MCP 采集: server={}, tool={}", event.serverName(), event.toolName());
    }

    @Override
    public void recordSkillActivation(SkillActivationEvent event) {
        log.debug("LangSmith 未启用，跳过技能激活采集: skill={}, source={}",
                event.skillId(), event.source());
    }
}
