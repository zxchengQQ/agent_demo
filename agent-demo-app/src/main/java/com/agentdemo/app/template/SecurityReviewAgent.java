package com.agentdemo.app.template;

import com.agentdemo.app.core.HumanCheckpoint;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 安全审查 Agent 接口（P2 并行模板）
 * <p>
 * 业务含义：从安全维度审查内容，并行模板"多角度审查"的安全审查分组（AC-004）。
 * 标注 @HumanCheckpoint：执行前需人工确认（工作流 HITL 端到端，AC-E02 并行排队）。
 * </p>
 */
public interface SecurityReviewAgent {

    @Agent
    @HumanCheckpoint(message = "确认执行安全审查？")
    @UserMessage("请从安全角度审查以下内容，输出安全审查报告：\n\n{{content}}")
    TokenStream execute(@V("content") String content);
}
