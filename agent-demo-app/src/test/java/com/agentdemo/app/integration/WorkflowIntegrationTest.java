package com.agentdemo.app.integration;

import com.agentdemo.app.adapter.AgenticAgentFactory;
import com.agentdemo.app.core.AgentDefinition;
import com.agentdemo.app.core.WorkflowExecution;
import com.agentdemo.app.core.WorkflowExecutionStatus;
import com.agentdemo.app.core.WorkflowTemplate;
import com.agentdemo.app.execution.AgentExecutor;
import com.agentdemo.app.registry.WorkflowTemplateRegistry;
import com.agentdemo.app.service.TestTokenStream;
import com.agentdemo.app.service.WorkflowExecutionService;
import com.agentdemo.app.strategy.SequentialExecutionStrategy;
import com.agentdemo.app.template.AnalysisAgent;
import com.agentdemo.app.template.ResearchAgent;
import com.agentdemo.app.template.ResearchAnalyzeSummarizeTemplate;
import com.agentdemo.app.template.SummaryAgent;
import com.agentdemo.tools.registry.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工作流端到端集成测试（P1 Task-15 / P2 Task-10 适配）
 * <p>
 * 业务含义：验证"模板自动注册 → 查询 → 串行执行 → 状态查询"的完整链路。
 * 使用真实 WorkflowTemplateRegistry + ResearchAnalyzeSummarizeTemplate（真实注册），
 * 使用真实策略（SequentialExecutionStrategy）+ AgenticAgentFactory 以 mock 隔离（避免真实 LLM 调用）。
 * </p>
 */
class WorkflowIntegrationTest {

    private WorkflowTemplateRegistry registry;
    private AgenticAgentFactory agentFactory;
    private WorkflowExecutionService service;

    @BeforeEach
    void setUp() {
        registry = new WorkflowTemplateRegistry();
        agentFactory = mock(AgenticAgentFactory.class);
        // P2 架构：真实串行策略 + mock AgentFactory 构建的 AgentExecutor
        AgentExecutor agentExecutor = new AgentExecutor(agentFactory);
        service = new WorkflowExecutionService(List.of(new SequentialExecutionStrategy(agentExecutor)));

        // 模拟预置模板注册（AC-024 启动时自动注册）
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ResearchAnalyzeSummarizeTemplate templateConfig = new ResearchAnalyzeSummarizeTemplate(toolRegistry);
        templateConfig.researchAnalyzeSummarizeWorkflow(registry);
    }

    @Test
    void templateList_shouldContainResearchAnalyzeSummarize() {
        List<WorkflowTemplate> templates = registry.listTemplates();
        assertEquals(1, templates.size());
        assertEquals("research-analyze-summarize", templates.get(0).getId());
        assertEquals("研究-分析-总结", templates.get(0).getName());
    }

    @Test
    void templateDetail_shouldHaveThreeAgents() {
        WorkflowTemplate template = registry.getTemplate("research-analyze-summarize");
        assertEquals(3, template.getAgents().size());
        assertEquals(ResearchAgent.class, template.getAgents().get(0).getInterfaceClass());
        assertEquals(AnalysisAgent.class, template.getAgents().get(1).getInterfaceClass());
        assertEquals(SummaryAgent.class, template.getAgents().get(2).getInterfaceClass());
    }

    @Test
    void fullExecution_shouldCompleteWithFinalResult() {
        // Mock 三个 Agent：返回固定文本，验证串行链路
        ResearchAgent researchAgent = mock(ResearchAgent.class);
        when(researchAgent.execute(any())).thenAnswer(inv -> {
            TestTokenStream stream = new TestTokenStream();
            stream.emitToken("研究");
            stream.completeWith("研究结果");
            return stream;
        });
        AnalysisAgent analysisAgent = mock(AnalysisAgent.class);
        when(analysisAgent.execute(any())).thenAnswer(inv -> {
            TestTokenStream stream = new TestTokenStream();
            stream.completeWith("分析结果");
            return stream;
        });
        SummaryAgent summaryAgent = mock(SummaryAgent.class);
        when(summaryAgent.execute(any())).thenAnswer(inv -> {
            TestTokenStream stream = new TestTokenStream();
            stream.completeWith("总结报告");
            return stream;
        });
        when(agentFactory.buildAgent(any())).thenReturn(researchAgent, analysisAgent, summaryAgent);

        WorkflowTemplate template = registry.getTemplate("research-analyze-summarize");
        SseEmitter emitter = mock(SseEmitter.class);
        String executionId = service.execute(template, Map.of("topic", "AI Agent 技术趋势"), emitter, null);

        // 等待完成
        long deadline = System.currentTimeMillis() + 5000;
        WorkflowExecution execution = null;
        while (System.currentTimeMillis() < deadline) {
            execution = service.getExecution(executionId);
            if (execution.getStatus() != WorkflowExecutionStatus.RUNNING) {
                break;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        assertNotNull(execution);
        assertEquals(WorkflowExecutionStatus.COMPLETED, execution.getStatus());
        assertEquals("总结报告", execution.getFinalResult());
        assertEquals(3, execution.getSteps().size());
    }
}
