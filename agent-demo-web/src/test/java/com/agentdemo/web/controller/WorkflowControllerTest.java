package com.agentdemo.web.controller;

import com.agentdemo.app.core.AgentDefinition;
import com.agentdemo.app.core.OrchestrationMode;
import com.agentdemo.app.core.ParameterDefinition;
import com.agentdemo.app.core.StepExecution;
import com.agentdemo.app.core.StepStatus;
import com.agentdemo.app.core.WorkflowExecution;
import com.agentdemo.app.core.WorkflowTemplate;
import com.agentdemo.app.registry.WorkflowTemplateRegistry;
import com.agentdemo.app.service.WorkflowExecutionService;
import com.agentdemo.app.template.ResearchAgent;
import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.common.result.Result;
import com.agentdemo.web.dto.WorkflowDetailResponse;
import com.agentdemo.web.dto.WorkflowExecuteRequest;
import com.agentdemo.web.dto.WorkflowExecutionResponse;
import com.agentdemo.web.dto.WorkflowExecutionSummaryResponse;
import com.agentdemo.web.dto.WorkflowTemplateResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * WorkflowController REST API 测试（Task-14）
 * <p>
 * 测试策略：直接调用 Controller 方法验证 Result 对象，Registry/Service 通过 mock 隔离。
 * </p>
 */
class WorkflowControllerTest {

    private WorkflowTemplateRegistry registry;
    private WorkflowExecutionService executionService;
    private WorkflowController controller;

    @BeforeEach
    void setUp() {
        registry = mock(WorkflowTemplateRegistry.class);
        executionService = mock(WorkflowExecutionService.class);
        controller = new WorkflowController(registry, executionService);
    }

    private WorkflowTemplate template() {
        return WorkflowTemplate.builder()
                .id("research-analyze-summarize")
                .name("研究-分析-总结")
                .description("串行工作流")
                .mode(OrchestrationMode.SEQUENTIAL)
                .maxRetries(3)
                .agents(List.of(AgentDefinition.builder()
                        .name("研究 Agent")
                        .description("d")
                        .modelId(null)
                        .toolIds(List.of("builtin:httpGet"))
                        .interfaceClass(ResearchAgent.class)
                        .build()))
                .parameters(List.of(ParameterDefinition.builder()
                        .name("topic")
                        .type("string")
                        .required(true)
                        .description("研究主题")
                        .build()))
                .build();
    }

    @Test
    void listTemplates_shouldReturnRegisteredTemplates() {
        doReturn(List.of(template())).when(registry).listTemplates();
        Result<List<WorkflowTemplateResponse>> result = controller.listTemplates();
        assertTrue(result.isSuccess());
        assertEquals(1, result.getData().size());
        assertEquals("research-analyze-summarize", result.getData().get(0).getId());
        assertEquals("SEQUENTIAL", result.getData().get(0).getMode());
    }

    @Test
    void getTemplate_shouldReturnDetailWithAgents() {
        doReturn(template()).when(registry).getTemplate("research-analyze-summarize");
        Result<WorkflowDetailResponse> result = controller.getTemplate("research-analyze-summarize");
        assertTrue(result.isSuccess());
        assertEquals(1, result.getData().getAgents().size());
        assertEquals("研究 Agent", result.getData().getAgents().get(0).getName());
        assertEquals(List.of("builtin:httpGet"), result.getData().getAgents().get(0).getTools());
    }

    @Test
    void getTemplate_shouldThrowWhenNotExist() {
        // 模板不存在：registry.getTemplate 抛 WORKFLOW_NOT_FOUND
        doThrow(new BusinessException(ErrorCode.WORKFLOW_NOT_FOUND, "non-existent"))
                .when(registry).getTemplate("non-existent");
        BusinessException ex = assertThrows(BusinessException.class, () -> controller.getTemplate("non-existent"));
        assertEquals(ErrorCode.WORKFLOW_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void getTemplate_shouldReturnLoopStructure() {
        WorkflowTemplate loopTemplate = WorkflowTemplate.builder()
                .id("quality-review-loop")
                .name("质量评分-修订")
                .mode(OrchestrationMode.LOOP)
                .maxRetries(5)
                .agents(List.of())
                .parameters(List.of())
                .loop(com.agentdemo.app.core.LoopDefinition.builder()
                        .maxIterations(5)
                        .exitConditionDescription("评分 ≥ 90 时退出")
                        .agents(List.of(AgentDefinition.builder().name("评分 Agent").build()))
                        .build())
                .build();
        doReturn(loopTemplate).when(registry).getTemplate("quality-review-loop");
        Result<WorkflowDetailResponse> result = controller.getTemplate("quality-review-loop");
        assertTrue(result.isSuccess());
        assertNotNull(result.getData().getLoop());
        assertEquals(5, result.getData().getLoop().getMaxIterations());
        assertEquals("评分 ≥ 90 时退出", result.getData().getLoop().getExitConditionDescription());
        assertEquals(1, result.getData().getLoop().getAgents().size());
        assertEquals("评分 Agent", result.getData().getLoop().getAgents().get(0).getName());
    }

    @Test
    void getTemplate_shouldReturnBranchesAndParallelGroups() {
        WorkflowTemplate complexTemplate = WorkflowTemplate.builder()
                .id("smart-routing")
                .name("智能路由")
                .mode(OrchestrationMode.CONDITIONAL)
                .maxRetries(3)
                .agents(List.of())
                .parameters(List.of())
                .branches(List.of(
                        com.agentdemo.app.core.BranchDefinition.builder()
                                .name("简单回答")
                                .conditionDescription("问题简短")
                                .condition(ctx -> true)
                                .agents(List.of(AgentDefinition.builder().name("简单 Agent").build()))
                                .build()))
                .parallelGroups(List.of(
                        com.agentdemo.app.core.ParallelGroup.builder()
                                .name("安全审查")
                                .agents(List.of(AgentDefinition.builder().name("安全 Agent").build()))
                                .build()))
                .build();
        doReturn(complexTemplate).when(registry).getTemplate("smart-routing");
        Result<WorkflowDetailResponse> result = controller.getTemplate("smart-routing");
        assertTrue(result.isSuccess());
        // 条件分支结构（Predicate 不序列化，仅展示描述与 Agent）
        assertNotNull(result.getData().getBranches());
        assertEquals(1, result.getData().getBranches().size());
        assertEquals("简单回答", result.getData().getBranches().get(0).getName());
        assertEquals("问题简短", result.getData().getBranches().get(0).getConditionDescription());
        assertEquals("简单 Agent", result.getData().getBranches().get(0).getAgents().get(0).getName());
        // 并行分组结构
        assertNotNull(result.getData().getParallelGroups());
        assertEquals(1, result.getData().getParallelGroups().size());
        assertEquals("安全审查", result.getData().getParallelGroups().get(0).getName());
        assertEquals("安全 Agent", result.getData().getParallelGroups().get(0).getAgents().get(0).getName());
    }

    @Test
    void execute_shouldReturnSseEmitter() {
        doReturn(template()).when(registry).getTemplate("research-analyze-summarize");
        WorkflowExecuteRequest request = new WorkflowExecuteRequest();
        request.setParameters(Map.of("topic", "AI Agent 技术趋势"));
        request.setModelId(null);

        SseEmitter emitter = controller.execute("research-analyze-summarize", request);
        assertNotNull(emitter);
        // 验证 service.execute 被调用
        verify(executionService).execute(eq(template()), eq(Map.of("topic", "AI Agent 技术趋势")),
                any(SseEmitter.class), eq(null));
    }

    @Test
    void execute_shouldThrowParamMissingWhenTopicMissing() {
        doReturn(template()).when(registry).getTemplate("research-analyze-summarize");
        WorkflowExecuteRequest request = new WorkflowExecuteRequest();
        request.setParameters(Map.of());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.execute("research-analyze-summarize", request));
        assertEquals(ErrorCode.WORKFLOW_PARAM_MISSING, ex.getErrorCode());
    }

    @Test
    void execute_shouldUseNeverTimeoutEmitter() throws Exception {
        // BUG 复现：emitter 5 分钟超时导致长任务中断 + 页面卡执行中，必须改为永不超时（0）
        doReturn(template()).when(registry).getTemplate("research-analyze-summarize");
        WorkflowExecuteRequest request = new WorkflowExecuteRequest();
        request.setParameters(Map.of("topic", "长任务"));
        request.setModelId(null);

        SseEmitter emitter = controller.execute("research-analyze-summarize", request);
        assertEquals(0L, readEmitterTimeout(emitter), "SSE emitter 应配置为永不超时（0L）");
    }

    @Test
    void resume_shouldUseNeverTimeoutEmitter() throws Exception {
        SseEmitter emitter = controller.resume("exec-1");
        assertEquals(0L, readEmitterTimeout(emitter), "恢复流 SSE emitter 应配置为永不超时（0L）");
    }

    /**
     * 反射读取 SseEmitter 的超时值（ResponseBodyEmitter.timeout 私有字段，无公开 getter）
     */
    private static long readEmitterTimeout(SseEmitter emitter) throws Exception {
        java.lang.reflect.Field field = org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.class
                .getDeclaredField("timeout");
        field.setAccessible(true);
        Long timeout = (Long) field.get(emitter);
        return timeout == null ? -1L : timeout;
    }

    @Test
    void getExecution_shouldReturnStatus() {
        WorkflowExecution execution = new WorkflowExecution("exec-1", "tpl-1", "研究-分析-总结");
        execution.start();
        execution.complete("最终结果");
        StepExecution step = new StepExecution("研究 Agent", 0, StepStatus.COMPLETED);
        step.complete("研究结果");
        execution.getSteps().add(step);

        doReturn(execution).when(executionService).getExecution("exec-1");
        Result<WorkflowExecutionResponse> result = controller.getExecution("exec-1");
        assertTrue(result.isSuccess());
        assertEquals("exec-1", result.getData().getExecutionId());
        assertEquals("COMPLETED", result.getData().getStatus());
        assertEquals("最终结果", result.getData().getFinalResult());
        assertEquals(1, result.getData().getSteps().size());
    }

    @Test
    void getExecution_shouldThrowWhenNotExist() {
        doReturn(null).when(executionService).getExecution("non-existent");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.getExecution("non-existent"));
        assertEquals(ErrorCode.WORKFLOW_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void terminate_shouldReturnSuccess() {
        Result<Void> result = controller.terminate("exec-1");
        assertTrue(result.isSuccess());
        verify(executionService).terminate("exec-1");
    }

    @Test
    void listExecutions_shouldReturnSummaryList() {
        WorkflowExecution exec1 = new WorkflowExecution("exec-1", "tpl-1", "质量评分-修订");
        exec1.setMode(OrchestrationMode.LOOP);
        exec1.setIterationCount(3);
        exec1.start();
        exec1.complete("修订结果");
        WorkflowExecution exec2 = new WorkflowExecution("exec-2", "tpl-2", "多角度审查");
        exec2.setMode(OrchestrationMode.PARALLEL);
        exec2.start();
        exec2.complete("审查报告");

        doReturn(List.of(exec1, exec2)).when(executionService).listExecutions();
        Result<List<WorkflowExecutionSummaryResponse>> result = controller.listExecutions();
        assertTrue(result.isSuccess());
        assertEquals(2, result.getData().size());
        assertEquals("exec-1", result.getData().get(0).getExecutionId());
        assertEquals("LOOP", result.getData().get(0).getMode());
        assertEquals(3, result.getData().get(0).getIterationCount());
        assertEquals("PARALLEL", result.getData().get(1).getMode());
    }

    @Test
    void listExecutions_empty_shouldReturnEmptyList() {
        doReturn(List.of()).when(executionService).listExecutions();
        Result<List<WorkflowExecutionSummaryResponse>> result = controller.listExecutions();
        assertTrue(result.isSuccess());
        assertEquals(0, result.getData().size());
    }

    @Test
    void getExecution_shouldIncludeModeAndIterationCount() {
        WorkflowExecution execution = new WorkflowExecution("exec-1", "tpl-1", "质量评分-修订");
        execution.setMode(OrchestrationMode.LOOP);
        execution.setIterationCount(5);
        execution.start();
        execution.complete("最终修订稿");

        doReturn(execution).when(executionService).getExecution("exec-1");
        Result<WorkflowExecutionResponse> result = controller.getExecution("exec-1");
        assertTrue(result.isSuccess());
        assertEquals("LOOP", result.getData().getMode());
        assertEquals(5, result.getData().getIterationCount());
    }

    // ===== P3 Task-17: resume 恢复端点（AC-017）=====

    @Test
    void resume_pausedExecution_shouldReturnSseEmitterAndDelegateToService() {
        SseEmitter emitter = controller.resume("exec-paused-1");

        assertNotNull(emitter, "恢复执行应返回 SseEmitter");
        verify(executionService).resume(eq("exec-paused-1"), any(SseEmitter.class));
    }

    @Test
    void resume_executionNotExist_shouldPropagateNotFound() {
        // 执行不存在：service 抛 WORKFLOW_NOT_FOUND，经 GlobalExceptionHandler 转 JSON
        doThrow(new BusinessException(ErrorCode.WORKFLOW_NOT_FOUND, "non-existent"))
                .when(executionService).resume(eq("non-existent"), any(SseEmitter.class));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.resume("non-existent"));
        assertEquals(ErrorCode.WORKFLOW_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void resume_completedExecution_shouldPropagateNotResumable() {
        // 状态非 PAUSED：service 抛 WORKFLOW_NOT_RESUMABLE（BR-APP-009）
        doThrow(new BusinessException(ErrorCode.WORKFLOW_NOT_RESUMABLE,
                "工作流当前状态不支持恢复: COMPLETED"))
                .when(executionService).resume(eq("exec-done"), any(SseEmitter.class));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.resume("exec-done"));
        assertEquals(ErrorCode.WORKFLOW_NOT_RESUMABLE, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("COMPLETED"));
    }

    // ===== P3 Task-18: 模板详情 supervisor 结构（AC-002/AC-007）=====

    /** 构造 SUPERVISOR 模板（结构对齐 TaskBreakdownSupervisorTemplate） */
    private WorkflowTemplate supervisorTemplate() {
        return WorkflowTemplate.builder()
                .id("task-breakdown-supervisor")
                .name("任务拆解-执行-汇总")
                .mode(OrchestrationMode.SUPERVISOR)
                .maxRetries(3)
                .agents(List.of())
                .parameters(List.of())
                .supervisor(com.agentdemo.app.core.SupervisorDefinition.builder()
                        .planAgent(AgentDefinition.builder().name("任务拆解(主控)")
                                .modelId("glm-5.2").toolIds(List.of()).build())
                        .workers(List.of(
                                AgentDefinition.builder().name("研究")
                                        .modelId("glm-5.2").toolIds(List.of()).build(),
                                AgentDefinition.builder().name("分析")
                                        .modelId("glm-5.2").toolIds(List.of()).build(),
                                AgentDefinition.builder().name("总结")
                                        .modelId("glm-5.2").toolIds(List.of()).build()))
                        .summarizeAgent(AgentDefinition.builder().name("综合汇总(主控)")
                                .modelId("glm-5.2").toolIds(List.of()).build())
                        .maxSubtasks(5)
                        .build())
                .build();
    }

    @Test
    void getTemplate_supervisorMode_shouldReturnSupervisorStructure() {
        doReturn(supervisorTemplate()).when(registry).getTemplate("task-breakdown-supervisor");
        Result<WorkflowDetailResponse> result = controller.getTemplate("task-breakdown-supervisor");
        assertTrue(result.isSuccess());

        WorkflowDetailResponse.SupervisorItem sup = result.getData().getSupervisor();
        assertNotNull(sup, "SUPERVISOR 模板应返回 supervisor 结构");
        assertEquals(5, sup.getMaxSubtasks());
        assertEquals("任务拆解(主控)", sup.getPlanAgent().getName());
        assertEquals("glm-5.2", sup.getPlanAgent().getModelId());
        assertEquals(3, sup.getWorkers().size());
        assertEquals("研究", sup.getWorkers().get(0).getName());
        assertEquals("glm-5.2", sup.getWorkers().get(0).getModelId());
        assertEquals("综合汇总(主控)", sup.getSummarizeAgent().getName());
        assertEquals("glm-5.2", sup.getSummarizeAgent().getModelId());
    }

    @Test
    void getTemplate_nonSupervisorMode_supervisorShouldBeNull() {
        // 既有 4 模板回归：SEQUENTIAL 模板 supervisor 为 null
        doReturn(template()).when(registry).getTemplate("research-analyze-summarize");
        Result<WorkflowDetailResponse> result = controller.getTemplate("research-analyze-summarize");
        assertTrue(result.isSuccess());
        assertNull(result.getData().getSupervisor(), "非 SUPERVISOR 模板 supervisor 应为 null");
        // P2 结构不受影响
        assertEquals(1, result.getData().getAgents().size());
    }
}
