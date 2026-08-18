package com.agentdemo.app.template;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 总结 Agent 接口
 * <p>
 * 业务含义：将分析内容总结为简洁明了的结论报告。
 * </p>
 */
public interface SummaryAgent {

    @Agent
    @UserMessage("请将以下分析内容总结为简洁明了的结论报告：\n\n分析内容：{{analysisResult}}")
    TokenStream execute(@V("analysisResult") String analysisResult);
}
