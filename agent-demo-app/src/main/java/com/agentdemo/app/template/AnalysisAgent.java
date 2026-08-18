package com.agentdemo.app.template;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 分析 Agent 接口
 * <p>
 * 业务含义：分析研究内容，提取关键洞察和趋势。
 * </p>
 */
public interface AnalysisAgent {

    @Agent
    @UserMessage("请分析以下研究内容，提取关键洞察和趋势：\n\n研究内容：{{researchResult}}")
    TokenStream execute(@V("researchResult") String researchResult);
}
