package com.agentdemo.web.controller;

import com.agentdemo.agent.core.HitlTokenStream;
import com.agentdemo.agent.core.HumanInteractionManager;
import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.agent.single.PlanAgent;
import com.agentdemo.agent.single.SimpleAgent;
import com.agentdemo.memory.shortterm.ChatMemoryManager;
import com.agentdemo.memory.session.SessionManager;
import com.agentdemo.tools.registry.ToolRegistry;
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
 * AgentController HITL 人机交互分流测试（Task-11）
 * <p>
 * 验证标准来源：Task-06 验证标准
 * 关联 AC：AC-N01（歧义追问）、AC-N03（恢复执行）、AC-T01/T02（ask_user 事件）、AC-E01（超时清理）
 * </p>
 */
@WebMvcTest(AgentController.class)
class AgentControllerHitlTest {

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
    @MockBean
    ToolRegistry toolRegistry;
    @MockBean
    AgentConfig agentConfig;
    @MockBean
    HumanInteractionManager humanInteractionManager;

    /**
     * mock HitlTokenStream 链式调用（避免 NPE）
     */
    private HitlTokenStream mockHitlStream() {
        HitlTokenStream hitlStream = mock(HitlTokenStream.class);
        when(hitlStream.onPartialThinking(any())).thenReturn(hitlStream);
        when(hitlStream.onPartialThought(any())).thenReturn(hitlStream);
        when(hitlStream.onPartialResponse(any())).thenReturn(hitlStream);
        when(hitlStream.onAction(any())).thenReturn(hitlStream);
        when(hitlStream.onObservation(any())).thenReturn(hitlStream);
        when(hitlStream.onFinalAnswer(any())).thenReturn(hitlStream);
        when(hitlStream.onAskUser(any())).thenReturn(hitlStream);
        when(hitlStream.onComplete(any())).thenReturn(hitlStream);
        when(hitlStream.onError(any())).thenReturn(hitlStream);
        return hitlStream;
    }

    // ========== 验证标准：enableHitl=true 路由到 HITL 路径 ==========

    /**
     * 验证标准 1：enableHitl=true 时走 HITL 路径（调用 simpleAgent.chatHITLStream）
     */
    @Test
    void shouldCallChatHitlStreamWhenEnableHitlIsTrue() throws Exception {
        when(sessionManager.createSession()).thenReturn("test-session-id");
        when(humanInteractionManager.hasPending(anyString())).thenReturn(false);
        HitlTokenStream hitlStream = mockHitlStream();
        when(simpleAgent.chatHITLStream(anyString(), anyString(), any(), any())).thenReturn(hitlStream);

        mockMvc.perform(post("/api/agent/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"帮我查订单\",\"enableHitl\":true}"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.TEXT_EVENT_STREAM));

        // 验证走了 HITL 路径
        verify(simpleAgent).chatHITLStream(anyString(), anyString(), any(), any());
        // 验证没走原路径
        verify(simpleAgent, never()).chatStream(anyString(), anyString(), any(), any());
    }

    /**
     * 验证标准 2：enableHitl=false 时走原路径（零回归）
     */
    @Test
    void shouldNotCallChatHitlStreamWhenEnableHitlIsFalse() throws Exception {
        when(sessionManager.createSession()).thenReturn("test-session-id");
        when(humanInteractionManager.hasPending(anyString())).thenReturn(false);

        TokenStream tokenStream = mock(TokenStream.class);
        when(tokenStream.onPartialResponse(any())).thenReturn(tokenStream);
        when(tokenStream.onCompleteResponse(any())).thenReturn(tokenStream);
        when(tokenStream.onError(any())).thenReturn(tokenStream);
        when(simpleAgent.chatStream(anyString(), anyString(), any(), any())).thenReturn(tokenStream);

        mockMvc.perform(post("/api/agent/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"你好\",\"enableHitl\":false}"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.TEXT_EVENT_STREAM));

        // 验证没走 HITL 路径
        verify(simpleAgent, never()).chatHITLStream(anyString(), anyString(), any(), any());
        // 验证走了原路径
        verify(simpleAgent).chatStream(anyString(), anyString(), any(), any());
    }

    // ========== 验证标准：HITL 恢复路径 ==========

    /**
     * 验证标准 3：有 pending 交互时走恢复路径（调用 simpleAgent.resumeHITLStream）
     * <p>
     * 业务含义：用户回复了 Agent 的提问，Controller 检测到 pending 状态，
     * 加载保存的 ReAct 上下文并继续推理（AC-N03）
     * </p>
     */
    @Test
    void shouldResumeHitlStreamWhenPendingExists() throws Exception {
        when(sessionManager.createSession()).thenReturn("test-session-id");
        when(humanInteractionManager.hasPending(anyString())).thenReturn(true);
        HitlTokenStream hitlStream = mockHitlStream();
        when(simpleAgent.resumeHITLStream(anyString(), anyString())).thenReturn(hitlStream);

        mockMvc.perform(post("/api/agent/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"ORD-12345\",\"sessionId\":\"test-session-id\"}"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.TEXT_EVENT_STREAM));

        // 验证走了恢复路径
        verify(simpleAgent).resumeHITLStream(anyString(), anyString());
        // 验证没创建新 HITL 对话（未调用 chatHITLStream）
        verify(simpleAgent, never()).chatHITLStream(anyString(), anyString(), any(), any());
    }

    /**
     * 验证标准 4：有 pending 但 resumeHITLStream 返回 null（状态已超时清理）时降级为正常流程
     * <p>
     * 业务含义：pending 状态可能已被超时清理，Controller 检测到无可用恢复时降级为正常对话（AC-E01，不报错）
     * </p>
     */
    @Test
    void shouldFallbackToNormalPathWhenResumeReturnsNull() throws Exception {
        when(sessionManager.createSession()).thenReturn("test-session-id");
        when(humanInteractionManager.hasPending(anyString())).thenReturn(true);
        when(simpleAgent.resumeHITLStream(anyString(), anyString())).thenReturn(null);

        TokenStream tokenStream = mock(TokenStream.class);
        when(tokenStream.onPartialResponse(any())).thenReturn(tokenStream);
        when(tokenStream.onCompleteResponse(any())).thenReturn(tokenStream);
        when(tokenStream.onError(any())).thenReturn(tokenStream);
        when(simpleAgent.chatStream(anyString(), anyString(), any(), any())).thenReturn(tokenStream);

        mockMvc.perform(post("/api/agent/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"你好\",\"sessionId\":\"test-session-id\"}"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.TEXT_EVENT_STREAM));

        // 验证降级为原路径
        verify(simpleAgent).chatStream(anyString(), anyString(), any(), any());
    }
}
