package com.agentdemo.evaluation.runner;

import com.agentdemo.observability.TraceCollector;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 工具轨迹录制采集器（langsmith-observability CR-002 Task-27）
 * <p>
 * 业务含义：评估 harness 运行时安装为全局 TraceCollector 的替身，仅采集
 * {@link #recordTool} 事件（工具名按调用序），供确定性评估器做工具选择断言
 * （AC-N10）。评估运行前 {@link #reset()} 清空，避免跨用例串扰。
 * 其余采集域（LLM/RAG/记忆/工作流/MCP/Skill）不参与本地评估留痕，空实现。
 * </p>
 */
public class RecordingTraceCollector implements TraceCollector {

    /** 单条工具执行记录（名 + 结果，供 judge 幻觉复核，结果截断） */
    private record ToolTrace(String name, String result) {
        static final int RESULT_MAX = 200;

        String display() {
            return result == null || result.isBlank() ? name : name + " → " + truncate(result);
        }

        private static String truncate(String s) {
            return s.length() > RESULT_MAX ? s.substring(0, RESULT_MAX) + "…[截断]" : s;
        }
    }

    private final List<ToolTrace> toolTraces = new CopyOnWriteArrayList<>();

    /** 清空已采集工具轨迹（每用例执行前调用） */
    public void reset() {
        toolTraces.clear();
    }

    /** 返回已采集工具名（按调用序，确定性工具选择断言用） */
    public List<String> toolNames() {
        return toolTraces.stream().map(ToolTrace::name).toList();
    }

    /** 返回含工具结果的轨迹文本（judge 幻觉复核用，形如 "name → result" 分号连接，结果截断） */
    public String toolTraceDetail() {
        return toolTraces.stream().map(ToolTrace::display)
                .collect(java.util.stream.Collectors.joining("; "));
    }

    @Override
    public void startRequest() {
        // 评估留痕不追踪请求级生命周期
    }

    @Override
    public void endRequest() {
        // 评估留痕不追踪请求级生命周期
    }

    @Override
    public void recordLlm(LlmCallEvent event) {
        // 评估留痕不采集 LLM span（Token 统计走 LangSmith 平台侧）
    }

    @Override
    public void recordTool(ToolCallEvent event) {
        if (event != null && event.toolName() != null) {
            toolTraces.add(new ToolTrace(event.toolName(), event.result()));
        }
    }

    @Override
    public void recordRag(RagRetrievalEvent event) {
        // 不参与本地评估留痕
    }

    @Override
    public void recordMemoryCompression(MemoryCompressionEvent event) {
        // 不参与本地评估留痕
    }

    @Override
    public void recordWorkflow(WorkflowExecutionEvent event) {
        // 不参与本地评估留痕
    }

    @Override
    public void recordWorkflowStep(WorkflowStepEvent event) {
        // 不参与本地评估留痕
    }

    @Override
    public void recordMcp(McpCallEvent event) {
        // 不参与本地评估留痕
    }

    @Override
    public void recordSkillActivation(SkillActivationEvent event) {
        // 不参与本地评估留痕
    }
}
