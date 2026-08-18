package com.agentdemo.app.template;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Supervisor 主控-汇总 Agent 接口（P3 Task-12，AC-007）
 * <p>
 * 业务含义：输入全部子任务结果文本，输出综合报告（场景提示词
 * app-supervisor-summarize.txt 约束汇总结构）。主控用强模型（BR-APP-012）。
 * </p>
 */
public interface SupervisorSummarizeAgent {

    @Agent
    @UserMessage("以下是各 Worker 子任务的执行结果，请汇总为综合报告：\n\n{{results}}")
    TokenStream execute(@V("results") String results);
}
