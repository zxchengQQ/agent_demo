package com.agentdemo.app.template;

import com.agentdemo.app.core.HumanCheckpoint;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 修订 Agent 接口（P2 循环模板）
 * <p>
 * 业务含义：根据评分意见对内容进行修订改进，循环模板"质量评分-修订"的修订环节（AC-006）。
 * 标注 @HumanCheckpoint：每次修订前需人工确认（工作流 HITL，AC-N02/AC-E03）。
 * </p>
 */
public interface ReviseAgent {

    @Agent
    @HumanCheckpoint(message = "确认执行内容修订？")
    @UserMessage("请根据质量评分意见对以下内容进行修订改进，输出修订后的完整内容：\n\n{{content}}")
    TokenStream execute(@V("content") String content);
}
