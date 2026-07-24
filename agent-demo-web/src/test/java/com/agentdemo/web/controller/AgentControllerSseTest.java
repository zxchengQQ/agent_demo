package com.agentdemo.web.controller;

import com.agentdemo.agent.core.ThinkingTokenStream;
import com.agentdemo.agent.single.PlanAgent;
import com.agentdemo.agent.single.SimpleAgent;
import com.agentdemo.memory.shortterm.ChatMemoryManager;
import com.agentdemo.memory.session.SessionManager;
import com.agentdemo.web.dto.ChatRequest;
import dev.langchain4j.service.TokenStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AgentController SSE 流式接口测试
 * <p>
 * 验证标准来源：T-04 验证标准（CR-001 扩展：enableThinking 分流）
 * 关联 AC：AC-002、AC-014、AC-015、AC-021、AC-022
 * </p>
 */
@WebMvcTest(AgentController.class)
class AgentControllerSseTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    SimpleAgent simpleAgent;
    @MockBean
    PlanAgent planAgent;
    @MockBean
    SessionManager sessionManager;
    @MockBean
    ChatMemoryManager memoryManager;

    /**
     * AC-014: 空消息应返回 400
     */
    @Test
    void shouldReturn400WhenMessageIsBlank() throws Exception {
        mockMvc.perform(post("/api/agent/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"\"}"))
            .andExpect(status().isBadRequest());
    }

    /**
     * AC-015: 超 4000 字符的消息应返回 400
     */
    @Test
    void shouldReturn400WhenMessageExceeds4000Chars() throws Exception {
        String longMessage = "a".repeat(4001);
        mockMvc.perform(post("/api/agent/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"" + longMessage + "\"}"))
            .andExpect(status().isBadRequest());
    }

    /**
     * AC-002: 正常请求应返回 200 + text/event-stream Content-Type
     */
    @Test
    void shouldReturnEventStreamForValidRequest() throws Exception {
        when(sessionManager.createSession()).thenReturn("test-session-id");

        // mock TokenStream 链式调用（避免 NPE）
        TokenStream tokenStream = mock(TokenStream.class);
        when(tokenStream.onPartialResponse(any())).thenReturn(tokenStream);
        when(tokenStream.onCompleteResponse(any())).thenReturn(tokenStream);
        when(tokenStream.onError(any())).thenReturn(tokenStream);
        when(simpleAgent.chatStream(anyString(), anyString())).thenReturn(tokenStream);

        mockMvc.perform(post("/api/agent/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"你好\"}"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.TEXT_EVENT_STREAM));
    }

    // ========== CR-001 新增：enableThinking 分流测试 ==========

    /**
     * 验证标准 1：请求 enableThinking=true 时，应走 ReAct 思考流式路径（调用 simpleAgent.chatThinkingReActStream）
     * 业务含义：开启深度思考时，Controller 分流到 ReAct 思考流式路径，推送 reasoning + thought + action + observation 事件
     */
    @Test
    void shouldCallChatThinkingStreamWhenEnableThinkingIsTrue() throws Exception {
        when(sessionManager.createSession()).thenReturn("test-session-id");

        // mock ThinkingTokenStream 链式调用（避免 NPE）
        ThinkingTokenStream thinkingStream = mock(ThinkingTokenStream.class);
        when(thinkingStream.onPartialThinking(any())).thenReturn(thinkingStream);
        when(thinkingStream.onPartialThought(any())).thenReturn(thinkingStream);
        when(thinkingStream.onAction(any())).thenReturn(thinkingStream);
        when(thinkingStream.onObservation(any())).thenReturn(thinkingStream);
        when(thinkingStream.onFinalAnswer(any())).thenReturn(thinkingStream);
        when(thinkingStream.onPartialResponse(any())).thenReturn(thinkingStream);
        when(thinkingStream.onComplete(any())).thenReturn(thinkingStream);
        when(thinkingStream.onError(any())).thenReturn(thinkingStream);
        when(simpleAgent.chatThinkingReActStream(anyString(), anyString())).thenReturn(thinkingStream);

        mockMvc.perform(post("/api/agent/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"你好\",\"enableThinking\":true}"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.TEXT_EVENT_STREAM));

        // 验证走了 ReAct 思考流式路径
        verify(simpleAgent).chatThinkingReActStream(anyString(), anyString());
        // 验证没走原路径（chatStream 未被调用）
        verify(simpleAgent, never()).chatStream(anyString(), anyString());
    }

    /**
     * 验证标准 2：请求 enableThinking=false 时，SSE 流与原行为一致（零回归）
     * 业务含义：未开启深度思考时，Controller 走原有 chatStream 路径
     */
    @Test
    void shouldNotCallChatThinkingStreamWhenEnableThinkingIsFalse() throws Exception {
        when(sessionManager.createSession()).thenReturn("test-session-id");

        // mock TokenStream 链式调用
        TokenStream tokenStream = mock(TokenStream.class);
        when(tokenStream.onPartialResponse(any())).thenReturn(tokenStream);
        when(tokenStream.onCompleteResponse(any())).thenReturn(tokenStream);
        when(tokenStream.onError(any())).thenReturn(tokenStream);
        when(simpleAgent.chatStream(anyString(), anyString())).thenReturn(tokenStream);

        mockMvc.perform(post("/api/agent/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"你好\",\"enableThinking\":false}"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.TEXT_EVENT_STREAM));

        // 验证走了原路径
        verify(simpleAgent).chatStream(anyString(), anyString());
        // 验证没走 ReAct 思考流式路径
        verify(simpleAgent, never()).chatThinkingReActStream(anyString(), anyString());
    }
}
