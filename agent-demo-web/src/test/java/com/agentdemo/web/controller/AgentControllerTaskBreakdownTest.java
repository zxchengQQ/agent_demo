package com.agentdemo.web.controller;

import com.agentdemo.agent.core.TaskBreakdownStream;
import com.agentdemo.agent.single.PlanAgent;
import com.agentdemo.agent.single.SimpleAgent;
import com.agentdemo.memory.shortterm.ChatMemoryManager;
import com.agentdemo.memory.session.SessionManager;
import dev.langchain4j.service.TokenStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AgentController 任务拆解分支测试（Task-06）
 * <p>
 * 验证标准来源：Task-06 验证标准
 * 关联 AC：AC-001（任务拆解入口）、AC-003（状态流转）、AC-004（总结）、
 *         AC-006（失败处理）、AC-007（停止）、AC-010（网络中断）
 * </p>
 */
@WebMvcTest(AgentController.class)
class AgentControllerTaskBreakdownTest {

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
     * 创建 Mock 的 TaskBreakdownStream（链式方法返回 this）
     */
    private TaskBreakdownStream mockTaskBreakdownStream() {
        TaskBreakdownStream stream = mock(TaskBreakdownStream.class);
        when(stream.onPlan(any())).thenReturn(stream);
        when(stream.onNoBreakdown(any())).thenReturn(stream);
        when(stream.onTaskStart(any())).thenReturn(stream);
        when(stream.onTaskToken(any())).thenReturn(stream);
        when(stream.onTaskReasoning(any())).thenReturn(stream);
        when(stream.onTaskThought(any())).thenReturn(stream);
        when(stream.onTaskAction(any())).thenReturn(stream);
        when(stream.onTaskObservation(any())).thenReturn(stream);
        when(stream.onTaskComplete(any())).thenReturn(stream);
        when(stream.onTaskFailed(any())).thenReturn(stream);
        when(stream.onTaskCancelled(any())).thenReturn(stream);
        when(stream.onSummaryToken(any())).thenReturn(stream);
        when(stream.onSummaryReasoning(any())).thenReturn(stream);
        when(stream.onComplete(any())).thenReturn(stream);
        when(stream.onError(any())).thenReturn(stream);
        return stream;
    }

    /**
     * 验证标准 1: enableTaskBreakdown=true 时，PlanAgent.chatTaskBreakdownStream 被调用
     * 业务含义：开启任务拆解时，Controller 分流到 PlanAgent 路径
     */
    @Test
    void shouldCallPlanAgentWhenEnableTaskBreakdownIsTrue() throws Exception {
        when(sessionManager.createSession()).thenReturn("test-session-id");

        TaskBreakdownStream taskStream = mockTaskBreakdownStream();
        when(planAgent.chatTaskBreakdownStream(anyString(), anyString(), anyBoolean()))
                .thenReturn(taskStream);

        mockMvc.perform(post("/api/agent/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"复杂任务\",\"enableTaskBreakdown\":true}"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.TEXT_EVENT_STREAM));

        // 验证走了任务拆解路径
        verify(planAgent).chatTaskBreakdownStream(anyString(), anyString(), anyBoolean());
        // 验证没走原路径
        verify(simpleAgent, never()).chatStream(anyString(), anyString());
        verify(simpleAgent, never()).chatThinkingReActStream(anyString(), anyString());
        // 验证 start() 被调用（异步执行，需 timeout 等待）
        verify(taskStream, timeout(2000)).start();
    }

    /**
     * 验证标准 2: enableTaskBreakdown=false 时，走现有 SimpleAgent 路径
     * 业务含义：未开启任务拆解时，Controller 走原有路径（零回归）
     */
    @Test
    void shouldNotCallPlanAgentWhenEnableTaskBreakdownIsFalse() throws Exception {
        when(sessionManager.createSession()).thenReturn("test-session-id");

        TokenStream tokenStream = mock(TokenStream.class);
        when(tokenStream.onPartialResponse(any())).thenReturn(tokenStream);
        when(tokenStream.onCompleteResponse(any())).thenReturn(tokenStream);
        when(tokenStream.onError(any())).thenReturn(tokenStream);
        when(simpleAgent.chatStream(anyString(), anyString())).thenReturn(tokenStream);

        mockMvc.perform(post("/api/agent/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"你好\",\"enableTaskBreakdown\":false}"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.TEXT_EVENT_STREAM));

        // 验证走了原路径
        verify(simpleAgent).chatStream(anyString(), anyString());
        // 验证没走任务拆解路径
        verify(planAgent, never()).chatTaskBreakdownStream(anyString(), anyString(), anyBoolean());
    }

    /**
     * 验证标准 3: 不传 enableTaskBreakdown 时，走现有路径（默认 false）
     * 业务含义：enableTaskBreakdown 默认 false，不传时行为与现有一致
     */
    @Test
    void shouldNotCallPlanAgentWhenEnableTaskBreakdowntNotPresent() throws Exception {
        when(sessionManager.createSession()).thenReturn("test-session-id");

        TokenStream tokenStream = mock(TokenStream.class);
        when(tokenStream.onPartialResponse(any())).thenReturn(tokenStream);
        when(tokenStream.onCompleteResponse(any())).thenReturn(tokenStream);
        when(tokenStream.onError(any())).thenReturn(tokenStream);
        when(simpleAgent.chatStream(anyString(), anyString())).thenReturn(tokenStream);

        mockMvc.perform(post("/api/agent/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"你好\"}"))
            .andExpect(status().isOk());

        verify(simpleAgent).chatStream(anyString(), anyString());
        verify(planAgent, never()).chatTaskBreakdownStream(anyString(), anyString(), anyBoolean());
    }

    /**
     * 验证标准 4: enableTaskBreakdown=true + enableThinking=true 时，两个参数都传递
     * 业务含义：拆解+思考模式共存（AC-011）
     */
    @Test
    void shouldPassEnableThinkingWhenBothFlagsTrue() throws Exception {
        when(sessionManager.createSession()).thenReturn("test-session-id");

        TaskBreakdownStream taskStream = mockTaskBreakdownStream();
        when(planAgent.chatTaskBreakdownStream(anyString(), anyString(), anyBoolean()))
                .thenReturn(taskStream);

        mockMvc.perform(post("/api/agent/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"复杂任务\",\"enableTaskBreakdown\":true,\"enableThinking\":true}"))
            .andExpect(status().isOk());

        // 验证 enableThinking=true 被传递给 PlanAgent
        verify(planAgent).chatTaskBreakdownStream(anyString(), anyString(), eq(true));
    }

    /**
     * 验证标准 5: start() 在异步线程中执行，而非请求线程
     * <p>
     * 业务含义：BUG 修复——任务拆解编排必须异步执行，确保 SSE 事件实时推送。
     * 若 start() 在请求线程同步执行，Spring SseEmitter 在 Controller 返回前无法初始化 handler，
     * 所有 send() 数据被缓存，前端无法实时看到任务拆解进度。
     * </p>
     */
    @Test
    void shouldExecuteStartAsynchronously() throws Exception {
        when(sessionManager.createSession()).thenReturn("test-session-id");

        TaskBreakdownStream taskStream = mockTaskBreakdownStream();
        String requestThreadName = Thread.currentThread().getName();
        AtomicReference<String> startThreadName = new AtomicReference<>();
        doAnswer(invocation -> {
            startThreadName.set(Thread.currentThread().getName());
            return null;
        }).when(taskStream).start();
        when(planAgent.chatTaskBreakdownStream(anyString(), anyString(), anyBoolean()))
                .thenReturn(taskStream);

        mockMvc.perform(post("/api/agent/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"复杂任务\",\"enableTaskBreakdown\":true}"))
            .andExpect(status().isOk());

        // 等待异步线程执行 start()
        verify(taskStream, timeout(2000)).start();

        // 验证 start() 在非请求线程中执行（证明异步执行）
        assertNotNull(startThreadName.get(), "start() 应被执行");
        assertNotEquals(requestThreadName, startThreadName.get(),
                "start() 应在异步线程中执行，而非请求线程");
    }

    /**
     * 验证标准 6: Controller 不阻塞等待 start() 完成
     * <p>
     * 业务含义：BUG 修复——Controller 必须立即返回 SseEmitter，不等任务拆解编排完成。
     * 通过让 start() 阻塞 2 秒，验证 Controller 在 1 秒内返回（证明不阻塞）。
     * </p>
     */
    @Test
    void shouldReturnImmediatelyWithoutBlockingOnStart() throws Exception {
        when(sessionManager.createSession()).thenReturn("test-session-id");

        TaskBreakdownStream taskStream = mockTaskBreakdownStream();
        CountDownLatch releaseLatch = new CountDownLatch(1);
        // 模拟 start() 阻塞，直到测试释放 latch
        doAnswer(invocation -> {
            releaseLatch.await();
            return null;
        }).when(taskStream).start();
        when(planAgent.chatTaskBreakdownStream(anyString(), anyString(), anyBoolean()))
                .thenReturn(taskStream);

        long startTime = System.currentTimeMillis();
        mockMvc.perform(post("/api/agent/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"复杂任务\",\"enableTaskBreakdown\":true}"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.TEXT_EVENT_STREAM));
        long elapsed = System.currentTimeMillis() - startTime;

        // 释放异步线程，避免影响后续测试
        releaseLatch.countDown();

        // Controller 应在 1 秒内返回（start() 阻塞在异步线程中），证明不阻塞
        assertTrue(elapsed < 1000,
                "Controller 应立即返回，不应等待 start() 完成，实际耗时: " + elapsed + "ms");
    }

    /**
     * 验证标准 7: 总结阶段 onSummaryToken 累积回复，onComplete 写入记忆
     * <p>
     * 业务含义：BUG 修复--任务拆解总结阶段的回复必须累积并写入记忆，
     * 保证下一轮对话有上下文。修复前 onSummaryToken 仅推送前端未累积，
     * onComplete 未调用 memoryManager.addAssistantMessage。
     * </p>
     */
    @Test
    void shouldSaveSummaryToMemoryOnComplete() throws Exception {
        when(sessionManager.createSession()).thenReturn("test-session-id");

        TaskBreakdownStream taskStream = mockTaskBreakdownStream();
        when(planAgent.chatTaskBreakdownStream(anyString(), anyString(), anyBoolean()))
                .thenReturn(taskStream);

        mockMvc.perform(post("/api/agent/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"复杂任务\",\"enableTaskBreakdown\":true}"))
            .andExpect(status().isOk());

        // 等待异步线程注册回调并调用 start()
        verify(taskStream, timeout(2000)).start();

        // 捕获 onSummaryToken 和 onComplete 回调
        ArgumentCaptor<TaskBreakdownStream.TokenConsumer> tokenCaptor =
                ArgumentCaptor.forClass(TaskBreakdownStream.TokenConsumer.class);
        ArgumentCaptor<Runnable> completeCaptor =
                ArgumentCaptor.forClass(Runnable.class);
        verify(taskStream).onSummaryToken(tokenCaptor.capture());
        verify(taskStream).onComplete(completeCaptor.capture());

        // 模拟总结阶段推送 token（验证 fullResponse 累积）
        tokenCaptor.getValue().accept("总结内容");
        // 模拟总结完成（验证记忆写入）
        completeCaptor.getValue().run();

        // 验证总结回复被写入记忆（BUG 修复核心断言）
        verify(memoryManager).addAssistantMessage(eq("test-session-id"), eq("总结内容"));
    }

    /**
     * 验证标准 8: 无总结内容时不写入空消息到记忆
     * <p>
     * 业务含义：当总结阶段未推送任何 token（如 LLM 返回空响应），
     * onComplete 不应将空字符串写入记忆。
     * </p>
     */
    @Test
    void shouldNotSaveEmptySummaryToMemory() throws Exception {
        when(sessionManager.createSession()).thenReturn("test-session-id");

        TaskBreakdownStream taskStream = mockTaskBreakdownStream();
        when(planAgent.chatTaskBreakdownStream(anyString(), anyString(), anyBoolean()))
                .thenReturn(taskStream);

        mockMvc.perform(post("/api/agent/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"复杂任务\",\"enableTaskBreakdown\":true}"))
            .andExpect(status().isOk());

        verify(taskStream, timeout(2000)).start();

        ArgumentCaptor<Runnable> completeCaptor =
                ArgumentCaptor.forClass(Runnable.class);
        verify(taskStream).onComplete(completeCaptor.capture());

        // 不推送任何 token，直接完成（模拟空响应）
        completeCaptor.getValue().run();

        // 验证不会将空字符串写入记忆（仅 addUserMessage 应被调用，无 addAssistantMessage）
        verify(memoryManager, never()).addAssistantMessage(anyString(), eq(""));
    }
}
