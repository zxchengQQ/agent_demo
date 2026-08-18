package com.agentdemo.app.template;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 简单回答 Agent 接口（P2 条件模板）
 * <p>
 * 业务含义：直接回答简单问题，条件模板"智能路由"的简单回答分支（AC-005）。
 * </p>
 */
public interface QuickAnswerAgent {

    @Agent
    @UserMessage("请直接回答以下问题：\n\n{{question}}")
    TokenStream execute(@V("question") String question);
}
