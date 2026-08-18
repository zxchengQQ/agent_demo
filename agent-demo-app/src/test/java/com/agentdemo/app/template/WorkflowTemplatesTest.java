package com.agentdemo.app.template;

import com.agentdemo.app.core.AgentDefinition;
import com.agentdemo.app.core.OrchestrationMode;
import com.agentdemo.app.core.ParallelGroup;
import com.agentdemo.app.core.WorkflowContext;
import com.agentdemo.app.core.WorkflowTemplate;
import com.agentdemo.app.registry.WorkflowTemplateRegistry;
import com.agentdemo.common.constant.ModelConstants;
import com.agentdemo.tools.registry.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * P2 预置模板测试（Task-13）
 * <p>
 * 业务含义：验证 3 个新模式预置模板（循环/条件/并行）结构完整、自动注册（AC-004/005/006/024）。
 * P3 扩展：任务拆解-执行-汇总 Supervisor 模板（Task-16，AC-007/AC-031）。
 * </p>
 */
class WorkflowTemplatesTest {

    private WorkflowTemplateRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new WorkflowTemplateRegistry();
        ToolRegistry toolRegistry = mock(ToolRegistry.class);

        new QualityReviewLoopTemplate(toolRegistry).qualityReviewLoopWorkflow(registry);
        new SmartRoutingTemplate(toolRegistry).smartRoutingWorkflow(registry);
        new MultiPerspectiveReviewTemplate(toolRegistry).multiPerspectiveReviewWorkflow(registry);
        new TaskBreakdownSupervisorTemplate(toolRegistry).taskBreakdownSupervisorWorkflow(registry);
        // P1 串行模板一并注册（生产 Spring 上下文 5 个预置模板全量注册，列表断言对齐）
        new ResearchAnalyzeSummarizeTemplate(toolRegistry).researchAnalyzeSummarizeWorkflow(registry);
    }

    @Test
    void qualityReviewLoop_shouldBeLoopModeWithMaxIterations() {
        WorkflowTemplate template = registry.getTemplate("quality-review-loop");
        assertNotNull(template);
        assertEquals(OrchestrationMode.LOOP, template.getMode());
        assertEquals("质量评分-修订", template.getName());
        assertNotNull(template.getLoop());
        assertEquals(5, template.getLoop().getMaxIterations());
        assertNotNull(template.getLoop().getExitCondition(), "退出条件谓词不能为空");
        assertNotNull(template.getLoop().getExitConditionDescription());
        // 循环体：评分 Agent + 修订 Agent
        assertEquals(2, template.getLoop().getAgents().size());
        assertEquals(ScoringAgent.class, template.getLoop().getAgents().get(0).getInterfaceClass());
        assertEquals(ReviseAgent.class, template.getLoop().getAgents().get(1).getInterfaceClass());
    }

    @Test
    void smartRouting_shouldBeConditionalModeWithTwoBranches() {
        WorkflowTemplate template = registry.getTemplate("smart-routing");
        assertNotNull(template);
        assertEquals(OrchestrationMode.CONDITIONAL, template.getMode());
        assertEquals("智能路由", template.getName());
        assertNotNull(template.getBranches());
        assertEquals(2, template.getBranches().size());
        // 分支 1：简单回答
        assertEquals("简单回答", template.getBranches().get(0).getName());
        assertNotNull(template.getBranches().get(0).getCondition());
        assertNotNull(template.getBranches().get(0).getConditionDescription());
        // 分支 2：复杂拆解
        assertEquals("复杂拆解", template.getBranches().get(1).getName());
        assertNotNull(template.getBranches().get(1).getCondition());
    }

    @Test
    void smartRouting_simpleQuestion_shouldMatchSimpleBranch() {
        WorkflowTemplate template = registry.getTemplate("smart-routing");
        WorkflowContext ctx = new WorkflowContext();
        ctx.write("question", "现在几点"); // 短问题
        assertTrue(template.getBranches().get(0).getCondition().test(ctx));
    }

    @Test
    void smartRouting_complexTaskQuestion_shouldMatchComplexBranch() {
        // BUG 复现：含复杂任务词（调研/总结报告）的问题即使长度 ≤80，也应走复杂拆解分支
        WorkflowTemplate template = registry.getTemplate("smart-routing");
        WorkflowContext ctx = new WorkflowContext();
        ctx.write("question", "帮我分阶段进行机器学习中分类、回归、聚合三个场景的算法模型的调研，然后给出一份总结报告给我");
        assertFalse(template.getBranches().get(0).getCondition().test(ctx),
                "含复杂任务词的问题不应命中简单回答分支");
        assertTrue(template.getBranches().get(1).getCondition().test(ctx),
                "含复杂任务词的问题应命中复杂拆解分支");
    }

    @Test
    void smartRouting_taskVerbQuestion_shouldMatchComplexBranch() {
        // 边界：以"帮我/请"引导的任务类问题（即使短）也应走复杂拆解分支
        WorkflowTemplate template = registry.getTemplate("smart-routing");
        WorkflowContext ctx = new WorkflowContext();
        ctx.write("question", "帮我设计一个登录系统");
        assertFalse(template.getBranches().get(0).getCondition().test(ctx));
        assertTrue(template.getBranches().get(1).getCondition().test(ctx));
    }

    @Test
    void multiPerspectiveReview_shouldBeParallelModeWithThreeGroups() {
        WorkflowTemplate template = registry.getTemplate("multi-perspective-review");
        assertNotNull(template);
        assertEquals(OrchestrationMode.PARALLEL, template.getMode());
        assertEquals("多角度审查", template.getName());
        assertNotNull(template.getParallelGroups());
        assertEquals(3, template.getParallelGroups().size());
        ParallelGroup group = template.getParallelGroups().get(0);
        assertNotNull(group.getName());
        assertEquals(1, group.getAgents().size());
    }

    @Test
    void templates_shouldHaveMaxRetriesConfigurable() {
        WorkflowTemplate loop = registry.getTemplate("quality-review-loop");
        WorkflowTemplate route = registry.getTemplate("smart-routing");
        WorkflowTemplate parallel = registry.getTemplate("multi-perspective-review");
        // 默认 3，循环模板可配置 5
        assertTrue(loop.getMaxRetries() >= 3);
        assertTrue(route.getMaxRetries() >= 3);
        assertTrue(parallel.getMaxRetries() >= 3);
    }

    @Test
    void templates_shouldHaveParameters() {
        assertNotNull(registry.getTemplate("quality-review-loop").getParameters());
        assertNotNull(registry.getTemplate("smart-routing").getParameters());
        assertNotNull(registry.getTemplate("multi-perspective-review").getParameters());
    }

    // ===== P3 Task-16: 任务拆解-执行-汇总 Supervisor 模板（AC-007/AC-031）=====

    @Test
    void taskBreakdownSupervisor_shouldBeSupervisorModeWithCompleteDefinition() {
        WorkflowTemplate template = registry.getTemplate("task-breakdown-supervisor");
        assertNotNull(template);
        assertEquals(OrchestrationMode.SUPERVISOR, template.getMode());
        assertFalse(template.getName().isBlank(), "模板名称不能为空");
        assertFalse(template.getDescription().isBlank(), "模板描述不能为空");
        assertNotNull(template.getSupervisor(), "SUPERVISOR 模板必须配置 supervisor 定义");
        assertEquals(5, template.getSupervisor().getMaxSubtasks());
    }

    @Test
    void taskBreakdownSupervisor_agents_shouldUseGlm52Model() {
        WorkflowTemplate template = registry.getTemplate("task-breakdown-supervisor");
        // AC-031 原语义：主控强模型 + Worker 快模型（需环境同时配置两类模型）。
        // 当前环境仅配置 glm-5.2，主控/Worker 统一降级为 glm-5.2（配置第二模型后可切回差异化）
        assertEquals(ModelConstants.MODEL_GLM_52,
                template.getSupervisor().getPlanAgent().getModelId());
        assertEquals(SupervisorPlanAgent.class,
                template.getSupervisor().getPlanAgent().getInterfaceClass());
        assertEquals(ModelConstants.MODEL_GLM_52,
                template.getSupervisor().getSummarizeAgent().getModelId());
        assertEquals(SupervisorSummarizeAgent.class,
                template.getSupervisor().getSummarizeAgent().getInterfaceClass());

        List<AgentDefinition> workers = template.getSupervisor().getWorkers();
        assertEquals(3, workers.size());
        for (AgentDefinition worker : workers) {
            assertEquals(ModelConstants.MODEL_GLM_52, worker.getModelId(),
                    "Worker 应全部使用 glm-5.2 模型: " + worker.getName());
        }
    }

    @Test
    void taskBreakdownSupervisor_workers_shouldReuseP1AgentInterfaces() {
        WorkflowTemplate template = registry.getTemplate("task-breakdown-supervisor");
        List<AgentDefinition> workers = template.getSupervisor().getWorkers();
        assertEquals("研究", workers.get(0).getName());
        assertEquals(ResearchAgent.class, workers.get(0).getInterfaceClass());
        assertEquals("分析", workers.get(1).getName());
        assertEquals(AnalysisAgent.class, workers.get(1).getInterfaceClass());
        assertEquals("总结", workers.get(2).getName());
        assertEquals(SummaryAgent.class, workers.get(2).getInterfaceClass());
    }

    @Test
    void taskBreakdownSupervisor_shouldHaveRequiredTaskParameter() {
        WorkflowTemplate template = registry.getTemplate("task-breakdown-supervisor");
        assertNotNull(template.getParameters());
        assertEquals(1, template.getParameters().size());
        assertEquals("task", template.getParameters().get(0).getName());
        assertEquals("string", template.getParameters().get(0).getType());
        assertTrue(template.getParameters().get(0).isRequired());
    }

    @Test
    void registry_shouldContainFivePresetTemplates() {
        List<WorkflowTemplate> all = registry.listTemplates();
        assertEquals(5, all.size(), "预置模板总数应为 5（P1 串行不在此 registry 实例 + P2 三个 + P3 一个）");
        assertNotNull(registry.getTemplate("task-breakdown-supervisor"));
    }
}
