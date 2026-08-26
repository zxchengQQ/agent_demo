package com.agentdemo.app.execution;

import com.agentdemo.app.adapter.AgenticAgentFactory;
import com.agentdemo.app.core.AgentDefinition;
import com.agentdemo.app.core.HumanCheckpoint;
import com.agentdemo.app.service.TestTokenStream;
import com.agentdemo.app.service.WorkflowHITLException;
import com.agentdemo.app.service.WorkflowHITLState;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentExecutor @HumanCheckpoint 检测测试（Task-05）
 * <p>
 * 验证标准来源：Task-05 验证标准
 * 关联 AC：AC-N02（模板预设检查点触发）、AC-T02（@HumanCheckpoint 注解检测）、AC-S01（检查点拒绝后终止）
 * </p>
 * <p>
 * 业务含义：@Agent 接口方法标注 @HumanCheckpoint 后，AgentExecutor 在执行前反射检测——
 * 有注解则构造确认型 HITL 快照（checkpoint 模式）并抛 WorkflowHITLException（不执行方法体），
 * 由协调层进入 WAITING_USER 等待人工确认；无注解走正常 TokenStream 路径（零回归）。
 * </p>
 */
class AgentExecutorCheckpointTest {

    /** 带 @HumanCheckpoint 注解的测试接口 */
    interface CheckpointAgent {
        @Agent
        @HumanCheckpoint(message = "确认执行分析步骤？")
        TokenStream analyze(@V("topic") String topic);
    }

    /** 无注解的对照接口（验证零回归） */
    interface PlainAgent {
        @Agent
        TokenStream execute(@V("topic") String topic);
    }

    private AgenticAgentFactory agentFactory;
    private AgentExecutor executor;

    @BeforeEach
    void setUp() {
        agentFactory = mock(AgenticAgentFactory.class);
        executor = new AgentExecutor(agentFactory);
    }

    private AgentDefinition checkpointAgentDef() {
        return AgentDefinition.builder()
                .name("分析 Agent")
                .description("d")
                .toolIds(List.of())
                .interfaceClass(CheckpointAgent.class)
                .build();
    }

    private AgentDefinition plainAgentDef() {
        return AgentDefinition.builder()
                .name("普通 Agent")
                .description("d")
                .toolIds(List.of())
                .interfaceClass(PlainAgent.class)
                .build();
    }

    private TestTokenStream fixedStream(String fullText) {
        TestTokenStream stream = new TestTokenStream();
        stream.completeWith(fullText);
        return stream;
    }

    // ========== 验证标准：检测到 @HumanCheckpoint 时，不执行方法体 ==========

    @Test
    void 注解方法执行前应抛WorkflowHITLException且不执行方法体() {
        CheckpointAgent agent = mock(CheckpointAgent.class);
        when(agent.analyze(anyString())).thenReturn(fixedStream("不应执行"));
        when(agentFactory.buildAgent(any())).thenReturn(agent);

        SseEmitter emitter = mock(SseEmitter.class);
        WorkflowHITLException ex = assertThrows(WorkflowHITLException.class,
                () -> executor.executeWithRetry(checkpointAgentDef(), "输入", emitter, 2, 0, null, 1, "exec-1"));

        // 方法体未被调用
        verify(agent, never()).analyze(anyString());
        // 快照模式为 checkpoint
        assertEquals(WorkflowHITLState.MODE_CHECKPOINT, ex.getHitlState().getHitlMode());
    }

    // ========== 验证标准：构造的 askUser 数据 type=confirm，携带确认/取消选项 ==========

    @Test
    void 快照askUser数据应为confirm类型并携带确认取消选项() {
        CheckpointAgent agent = mock(CheckpointAgent.class);
        when(agent.analyze(anyString())).thenReturn(fixedStream("不应执行"));
        when(agentFactory.buildAgent(any())).thenReturn(agent);

        SseEmitter emitter = mock(SseEmitter.class);
        WorkflowHITLException ex = assertThrows(WorkflowHITLException.class,
                () -> executor.executeWithRetry(checkpointAgentDef(), "输入", emitter, 0, 0, null, 0, "exec-1"));

        WorkflowHITLState.AskUserData askUserData = ex.getHitlState().getAskUserData();
        assertEquals("confirm", askUserData.getType());
        assertEquals("确认执行分析步骤？", askUserData.getQuestion());
        assertEquals(List.of("确认", "取消"), askUserData.getOptions());
    }

    // ========== 验证标准：快照携带暂停步骤位置（agentIndex/agentName/input/iteration） ==========

    @Test
    void 快照pendingStep应携带暂停步骤位置() {
        CheckpointAgent agent = mock(CheckpointAgent.class);
        when(agent.analyze(anyString())).thenReturn(fixedStream("不应执行"));
        when(agentFactory.buildAgent(any())).thenReturn(agent);

        SseEmitter emitter = mock(SseEmitter.class);
        WorkflowHITLException ex = assertThrows(WorkflowHITLException.class,
                () -> executor.executeWithRetry(checkpointAgentDef(), "本次输入", emitter, 3, 0, null, 5, "exec-1"));

        WorkflowHITLState.PendingStep pendingStep = ex.getHitlState().getPendingStep();
        assertEquals(3, pendingStep.getAgentIndex());
        assertEquals("分析 Agent", pendingStep.getAgentName());
        assertEquals("本次输入", pendingStep.getInput());
        assertEquals(5, pendingStep.getIteration());
    }

    // ========== 验证标准：checkpoint 异常不触发重试（maxRetries>0 也不重试） ==========

    @Test
    void checkpoint异常不应触发重试() {
        CheckpointAgent agent = mock(CheckpointAgent.class);
        when(agent.analyze(anyString())).thenReturn(fixedStream("不应执行"));
        when(agentFactory.buildAgent(any())).thenReturn(agent);

        SseEmitter emitter = mock(SseEmitter.class);
        assertThrows(WorkflowHITLException.class,
                () -> executor.executeWithRetry(checkpointAgentDef(), "输入", emitter, 0, 3, null, 0, "exec-1"));

        // 重试循环立即终止，方法体始终未被调用
        verify(agent, never()).analyze(anyString());
    }

    // ========== 验证标准：无注解时走正常 TokenStream 路径（零回归） ==========

    @Test
    void 无注解方法应正常执行() {
        PlainAgent agent = mock(PlainAgent.class);
        when(agent.execute(anyString())).thenReturn(fixedStream("正常输出"));
        when(agentFactory.buildAgent(any())).thenReturn(agent);

        SseEmitter emitter = mock(SseEmitter.class);
        String output = executor.executeWithRetry(plainAgentDef(), "输入", emitter, 0, 0, null, 0, "exec-1");

        assertEquals("正常输出", output);
        verify(agent).execute("输入");
        // 无异常抛出，方法体正常执行
        assertTrue(agentFactory != null);
    }
}
