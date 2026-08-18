package com.agentdemo.app.template;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 修订 Agent 接口（P2 循环模板）
 * <p>
 * 业务含义：根据评分意见对内容进行修订改进，循环模板"质量评分-修订"的修订环节（AC-006）。
 * </p>
 */
public interface ReviseAgent {

    @Agent
    @UserMessage("请根据质量评分意见对以下内容进行修订改进，输出修订后的完整内容：\n\n{{content}}")
    TokenStream execute(@V("content") String content);
}
