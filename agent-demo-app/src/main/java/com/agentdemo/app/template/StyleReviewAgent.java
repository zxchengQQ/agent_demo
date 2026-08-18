package com.agentdemo.app.template;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 风格审查 Agent 接口（P2 并行模板）
 * <p>
 * 业务含义：从代码风格与可维护性维度审查内容，并行模板"多角度审查"的风格审查分组（AC-004）。
 * </p>
 */
public interface StyleReviewAgent {

    @Agent
    @UserMessage("请从代码风格与可维护性角度审查以下内容，输出风格审查报告：\n\n{{content}}")
    TokenStream execute(@V("content") String content);
}
