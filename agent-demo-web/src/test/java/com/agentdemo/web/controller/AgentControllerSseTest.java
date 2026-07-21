package com.agentdemo.web.controller;

import com.agentdemo.agent.core.BaseAgent;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AgentController SSE 流式接口测试
 * <p>
 * 验证标准来源：T-04 验证标准
 * 关联 AC：AC-002、AC-014、AC-015
 * </p>
 */
@WebMvcTest(AgentController.class)
class AgentControllerSseTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    BaseAgent agent;
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
        when(agent.chatStream(anyString(), anyString())).thenReturn(tokenStream);

        mockMvc.perform(post("/api/agent/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"你好\"}"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.TEXT_EVENT_STREAM));
    }
}
