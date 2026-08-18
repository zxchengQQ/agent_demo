package com.agentdemo.app.template;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 评分 Agent 接口（P2 循环模板）
 * <p>
 * 业务含义：对内容进行质量评估打分（0-100），循环模板"质量评分-修订"的评分环节（AC-006）。
 * </p>
 */
public interface ScoringAgent {

    @Agent
    @UserMessage("请对以下内容进行质量评分（0-100），并给出评估说明：\n\n{{content}}")
    TokenStream execute(@V("content") String content);
}
