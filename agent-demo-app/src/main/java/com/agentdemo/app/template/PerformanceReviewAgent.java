package com.agentdemo.app.template;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 性能审查 Agent 接口（P2 并行模板）
 * <p>
 * 业务含义：从性能维度审查内容，并行模板"多角度审查"的性能审查分组（AC-004）。
 * </p>
 */
public interface PerformanceReviewAgent {

    @Agent
    @UserMessage("请从性能角度审查以下内容，输出性能审查报告：\n\n{{content}}")
    TokenStream execute(@V("content") String content);
}
