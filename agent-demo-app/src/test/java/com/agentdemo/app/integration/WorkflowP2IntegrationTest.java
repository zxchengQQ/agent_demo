package com.agentdemo.app.integration;

import com.agentdemo.app.adapter.AgenticAgentFactory;
import com.agentdemo.app.core.WorkflowExecution;
import com.agentdemo.app.core.WorkflowExecutionStatus;
import com.agentdemo.app.core.WorkflowTemplate;
import com.agentdemo.app.execution.AgentExecutor;
import com.agentdemo.app.registry.WorkflowTemplateRegistry;
import com.agentdemo.app.service.TestTokenStream;
import com.agentdemo.app.service.WorkflowExecutionService;
import com.agentdemo.app.strategy.ConditionalExecutionStrategy;
import com.agentdemo.app.strategy.LoopExecutionStrategy;
import com.agentdemo.app.strategy.ParallelExecutionStrategy;
import com.agentdemo.app.strategy.SequentialExecutionStrategy;
import com.agentdemo.app.template.MultiPerspectiveReviewTemplate;
import com.agentdemo.app.template.QualityReviewLoopTemplate;
import com.agentdemo.app.template.ReviseAgent;
import com.agentdemo.app.template.ScoringAgent;
import com.agentdemo.app.template.SecurityReviewAgent;
import com.agentdemo.app.template.SmartRoutingTemplate;
import com.agentdemo.tools.registry.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P2 端到端集成测试（Task-21）
 * <p>
 * 业务含义：验证"4 个模板自动注册 → 执行（LOOP 模式）→ 执行历史"的完整链路。
 * 使用真实 Registry + 真实 4 个策略 + mock AgentFactory 隔离 LLM（AC-004/005/006/024/027/029）。
 * </p>
 */
class WorkflowP2IntegrationTest {

    private WorkflowTemplateRegistry registry;
    private AgenticAgentFactory agentFactory;
    private WorkflowExecutionService service;

    @BeforeEach
    void setUp() {
        registry = new WorkflowTemplateRegistry();
        agentFactory = mock(AgenticAgentFactory.class);
        // P2 架构：真实 4 策略 + mock AgentFactory 构建的 AgentExecutor
        AgentExecutor agentExecutor = new AgentExecutor(agentFactory);
        service = new WorkflowExecutionService(List.of(
                new SequentialExecutionStrategy(agentExecutor),
                new ParallelExecutionStrategy(agentExecutor),
                new ConditionalExecutionStrategy(agentExecutor),
                new LoopExecutionStrategy(agentExecutor)));

        // 注册 4 个模板（P1 串行 + P2 三新模式，AC-024）
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        new QualityReviewLoopTemplate(toolRegistry).qualityReviewLoopWorkflow(registry);
        new SmartRoutingTemplate(toolRegistry).smartRoutingWorkflow(registry);
        new MultiPerspectiveReviewTemplate(toolRegistry).multiPerspectiveReviewWorkflow(registry);
    }

    @Test
    void templateList_shouldContainThreeP2Templates() {
        List<WorkflowTemplate> templates = registry.listTemplates();
        assertEquals(3, templates.size());
        assertTrue(templates.stream().anyMatch(t -> t.getId().equals("quality-review-loop")));
        assertTrue(templates.stream().anyMatch(t -> t.getId().equals("smart-routing")));
        assertTrue(templates.stream().anyMatch(t -> t.getId().equals("multi-perspective-review")));
    }

    @Test
    void loopExecution_shouldCompleteWithIterationCount() {
        // Mock 评分 Agent（第 1 轮输出 95 分）与修订 Agent
        ScoringAgent scoringAgent = mock(ScoringAgent.class);
        when(scoringAgent.execute(any())).thenAnswer(inv -> {
            TestTokenStream stream = new TestTokenStream();
            stream.completeWith("评分：95\n质量优秀");
            return stream;
        });
        ReviseAgent reviseAgent = mock(ReviseAgent.class);
        when(reviseAgent.execute(any())).thenAnswer(inv -> {
            TestTokenStream stream = new TestTokenStream();
            stream.completeWith("修订稿");
            return stream;
        });
        when(agentFactory.buildAgent(any())).thenReturn(scoringAgent, reviseAgent);

        WorkflowTemplate template = registry.getTemplate("quality-review-loop");
        SseEmitter emitter = mock(SseEmitter.class);
        String executionId = service.execute(template, Map.of("content", "初稿"), emitter, null);

        WorkflowExecution execution = awaitTerminal(executionId);
        assertEquals(WorkflowExecutionStatus.COMPLETED, execution.getStatus());
        // 评分 95 ≥ 90 → 第一轮后退出，iterationCount=1
        assertEquals(1, execution.getIterationCount());
        assertEquals("修订稿", execution.getFinalResult());
        // mode 记录
        assertEquals("LOOP", execution.getMode().name());
    }

    @Test
    void executionHistory_shouldIncludeModeAndIterationCount() {
        // 执行一个 LOOP 工作流后查询历史
        ScoringAgent scoringAgent = mock(ScoringAgent.class);
        when(scoringAgent.execute(any())).thenAnswer(inv -> {
            TestTokenStream stream = new TestTokenStream();
            stream.completeWith("评分：92");
            return stream;
        });
        ReviseAgent reviseAgent = mock(ReviseAgent.class);
        when(reviseAgent.execute(any())).thenAnswer(inv -> {
            TestTokenStream stream = new TestTokenStream();
            stream.completeWith("修订稿");
            return stream;
        });
        when(agentFactory.buildAgent(any())).thenReturn(scoringAgent, reviseAgent);

        WorkflowTemplate template = registry.getTemplate("quality-review-loop");
        String executionId = service.execute(template, Map.of("content", "初稿"), mock(SseEmitter.class), null);
        awaitTerminal(executionId);

        List<WorkflowExecution> history = service.listExecutions();
        assertEquals(1, history.size());
        assertEquals("LOOP", history.get(0).getMode().name());
        assertEquals(1, history.get(0).getIterationCount());
        assertEquals(executionId, history.get(0).getExecutionId());
    }

    /** 等待异步执行完成 */
    private WorkflowExecution awaitTerminal(String executionId) {
        long deadline = System.currentTimeMillis() + 5000;
        WorkflowExecution execution = null;
        while (System.currentTimeMillis() < deadline) {
            execution = service.getExecution(executionId);
            if (execution != null && execution.getStatus() != WorkflowExecutionStatus.RUNNING) {
                return execution;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertNotNull(execution, "执行应在超时内完成");
        return execution;
    }
}
