package com.agentdemo.app.template;

import com.agentdemo.app.core.HumanCheckpoint;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 研究 Agent 接口
 * <p>
 * 业务含义：负责对指定主题进行深入研究，收集关键信息和事实。
 * 通过 @Agent 注解声明为 Agentic Agent，返回 TokenStream 支持流式输出。
 * 标注 @HumanCheckpoint：执行研究前需人工确认（工作流 HITL，AC-N02/AC-N04）。
 * </p>
 */
public interface ResearchAgent {

    @Agent
    @HumanCheckpoint(message = "确认执行研究步骤？")
    @UserMessage("请针对以下主题进行深入研究，收集关键信息和事实：\n\n主题：{{topic}}")
    TokenStream execute(@V("topic") String topic);
}
