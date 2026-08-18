package com.agentdemo.web.controller;

import com.agentdemo.app.core.AgentDefinition;
import com.agentdemo.app.core.ParameterDefinition;
import com.agentdemo.app.core.StepExecution;
import com.agentdemo.app.core.WorkflowExecution;
import com.agentdemo.app.core.WorkflowTemplate;
import com.agentdemo.app.registry.WorkflowTemplateRegistry;
import com.agentdemo.app.service.WorkflowExecutionService;
import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.common.result.Result;
import com.agentdemo.web.dto.WorkflowDetailResponse;
import com.agentdemo.web.dto.WorkflowExecuteRequest;
import com.agentdemo.web.dto.WorkflowExecutionResponse;
import com.agentdemo.web.dto.WorkflowExecutionSummaryResponse;
import com.agentdemo.web.dto.WorkflowTemplateResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工作流 REST API
 * <p>
 * 业务含义：提供工作流模板管理（列表/详情）与执行（执行/查询/终止）的 REST 接口。
 * 执行接口通过 SSE 实时推送进度与 Agent 流式输出（AC-003/AC-008/AC-009/AC-010）。
 * </p>
 */
@Tag(name = "工作流", description = "多 Agent 工作流编排接口")
@RestController
@RequestMapping("/api/app/workflows")
public class WorkflowController {

    private static final Logger log = LoggerFactory.getLogger(WorkflowController.class);

    private final WorkflowTemplateRegistry registry;
    private final WorkflowExecutionService executionService;

    public WorkflowController(WorkflowTemplateRegistry registry,
                              WorkflowExecutionService executionService) {
        this.registry = registry;
        this.executionService = executionService;
    }

    /**
     * 查询模板列表（AC-001）
     */
    @Operation(summary = "查询工作流模板列表", description = "获取所有已注册的工作流模板摘要")
    @GetMapping
    public Result<List<WorkflowTemplateResponse>> listTemplates() {
        List<WorkflowTemplateResponse> responses = registry.listTemplates().stream()
                .map(t -> WorkflowTemplateResponse.builder()
                        .id(t.getId())
                        .name(t.getName())
                        .description(t.getDescription())
                        .mode(t.getMode().name())
                        .agentCount(t.getAgents().size())
                        .parameters(t.getParameters())
                        .build())
                .collect(Collectors.toList());
        return Result.success(responses);
    }

    /**
     * 查询模板详情（AC-002，P2 扩展：含并行/条件/循环编排结构）
     */
    @Operation(summary = "查询模板详情", description = "获取单个工作流模板的完整信息，含 Agent 配置与并行/条件/循环编排结构")
    @GetMapping("/{templateId}")
    public Result<WorkflowDetailResponse> getTemplate(@PathVariable String templateId) {
        WorkflowTemplate template = registry.getTemplate(templateId);
        List<WorkflowDetailResponse.AgentItem> agents = template.getAgents().stream()
                .map(this::toAgentItem)
                .collect(Collectors.toList());

        // P2 新增：并行分组结构（AC-004）
        List<WorkflowDetailResponse.ParallelGroupItem> parallelGroups = null;
        if (template.getParallelGroups() != null) {
            parallelGroups = template.getParallelGroups().stream()
                    .map(g -> WorkflowDetailResponse.ParallelGroupItem.builder()
                            .name(g.getName())
                            .agents(g.getAgents().stream().map(this::toAgentItem).collect(Collectors.toList()))
                            .build())
                    .collect(Collectors.toList());
        }

        // P2 新增：条件分支结构（Predicate 不序列化，仅展示描述，AC-005/BR-APP-015）
        List<WorkflowDetailResponse.BranchItem> branches = null;
        if (template.getBranches() != null) {
            branches = template.getBranches().stream()
                    .map(b -> WorkflowDetailResponse.BranchItem.builder()
                            .name(b.getName())
                            .conditionDescription(b.getConditionDescription())
                            .agents(b.getAgents().stream().map(this::toAgentItem).collect(Collectors.toList()))
                            .build())
                    .collect(Collectors.toList());
        }

        // P2 新增：循环定义结构（AC-006/AC-029）
        WorkflowDetailResponse.LoopItem loop = null;
        if (template.getLoop() != null) {
            loop = WorkflowDetailResponse.LoopItem.builder()
                    .maxIterations(template.getLoop().getMaxIterations())
                    .exitConditionDescription(template.getLoop().getExitConditionDescription())
                    .agents(template.getLoop().getAgents().stream().map(this::toAgentItem).collect(Collectors.toList()))
                    .build();
        }

        // P3 新增：Supervisor 层级编排结构（AC-002/AC-007）
        WorkflowDetailResponse.SupervisorItem supervisor = null;
        if (template.getSupervisor() != null) {
            supervisor = WorkflowDetailResponse.SupervisorItem.builder()
                    .maxSubtasks(template.getSupervisor().getMaxSubtasks())
                    .planAgent(toAgentItem(template.getSupervisor().getPlanAgent()))
                    .workers(template.getSupervisor().getWorkers().stream()
                            .map(this::toAgentItem).collect(Collectors.toList()))
                    .summarizeAgent(toAgentItem(template.getSupervisor().getSummarizeAgent()))
                    .build();
        }

        return Result.success(WorkflowDetailResponse.builder()
                .id(template.getId())
                .name(template.getName())
                .description(template.getDescription())
                .mode(template.getMode().name())
                .maxRetries(template.getMaxRetries())
                .agents(agents)
                .parameters(template.getParameters())
                .parallelGroups(parallelGroups)
                .branches(branches)
                .loop(loop)
                .supervisor(supervisor)
                .build());
    }

    /** Agent 定义 → 详情 DTO（统一映射，供顶层/并行/条件/循环复用） */
    private WorkflowDetailResponse.AgentItem toAgentItem(AgentDefinition a) {
        return WorkflowDetailResponse.AgentItem.builder()
                .name(a.getName())
                .description(a.getDescription())
                .modelId(a.getModelId())
                .tools(a.getToolIds())
                .build();
    }

    /**
     * 执行工作流（AC-003/AC-008/AC-009/AC-010）
     * <p>
     * 业务含义：异步执行工作流，通过 SSE 推送执行进度与 Agent 流式输出。
     * 校验必填参数（AC-014），缺少时返回 WORKFLOW_PARAM_MISSING。
     * </p>
     */
    @Operation(summary = "执行工作流", description = "异步执行工作流，通过 SSE 实时推送执行进度与流式输出")
    @PostMapping(value = "/{templateId}/execute", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter execute(@PathVariable String templateId,
                              @Valid @RequestBody WorkflowExecuteRequest request) {
        WorkflowTemplate template = registry.getTemplate(templateId);

        // 业务含义：手动校验模板必填参数是否齐全（AC-014）
        Map<String, Object> parameters = request.getParameters();
        List<String> missingParams = new ArrayList<>();
        for (ParameterDefinition param : template.getParameters()) {
            if (param.isRequired() && (parameters == null
                    || parameters.get(param.getName()) == null
                    || String.valueOf(parameters.get(param.getName())).isBlank())) {
                missingParams.add(param.getName());
            }
        }
        if (!missingParams.isEmpty()) {
            throw new BusinessException(ErrorCode.WORKFLOW_PARAM_MISSING,
                    "缺少必填参数: " + String.join(", ", missingParams));
        }

        // 业务含义：长任务工作流可能远超 5 分钟，emitter 固定超时会掐断 SSE 并
        // 触发 onTimeout→cancel 终止执行（BUG：页面卡执行中且流程中断）。
        // 0 = 永不超时（Servlet 规范），客户端真实断开由 onError→cancel 兜底
        SseEmitter emitter = new SseEmitter(0L);
        executionService.execute(template, parameters, emitter, request.getModelId());
        return emitter;
    }

    /**
     * 查询执行状态（AC-011）
     */
    @Operation(summary = "查询执行状态", description = "查询工作流执行实例的状态和结果")
    @GetMapping("/executions/{executionId}")
    public Result<WorkflowExecutionResponse> getExecution(@PathVariable String executionId) {
        WorkflowExecution execution = executionService.getExecution(executionId);
        if (execution == null) {
            throw new BusinessException(ErrorCode.WORKFLOW_NOT_FOUND, executionId);
        }
        List<WorkflowExecutionResponse.StepItem> steps = execution.getSteps().stream()
                .map(s -> WorkflowExecutionResponse.StepItem.builder()
                        .agentName(s.getAgentName())
                        .status(s.getStatus().name())
                        .durationMs(s.getDurationMs())
                        .build())
                .collect(Collectors.toList());
        return Result.success(WorkflowExecutionResponse.builder()
                .executionId(execution.getExecutionId())
                .templateId(execution.getTemplateId())
                .templateName(execution.getTemplateName())
                .status(execution.getStatus().name())
                .startTime(execution.getStartTime())
                .endTime(execution.getEndTime())
                .steps(steps)
                .finalResult(execution.getFinalResult())
                .mode(execution.getMode() != null ? execution.getMode().name() : null)
                .iterationCount(execution.getIterationCount())
                .build());
    }

    /**
     * 查询执行历史列表（AC-027，P2 新增）
     * <p>
     * 业务含义：获取所有执行实例摘要（按开始时间倒序），供前端执行历史列表展示。
     * 与 GET /executions/{id} 路径不冲突，Spring MVC 按路径段精确匹配。
     * </p>
     */
    @Operation(summary = "查询执行历史", description = "获取所有工作流执行实例列表（按开始时间倒序）")
    @GetMapping("/executions")
    public Result<List<WorkflowExecutionSummaryResponse>> listExecutions() {
        List<WorkflowExecutionSummaryResponse> responses = executionService.listExecutions().stream()
                .map(e -> WorkflowExecutionSummaryResponse.builder()
                        .executionId(e.getExecutionId())
                        .templateId(e.getTemplateId())
                        .templateName(e.getTemplateName())
                        .mode(e.getMode() != null ? e.getMode().name() : null)
                        .status(e.getStatus().name())
                        .startTime(e.getStartTime())
                        .endTime(e.getEndTime())
                        .finalResult(e.getFinalResult())
                        .iterationCount(e.getIterationCount())
                        .build())
                .collect(Collectors.toList());
        return Result.success(responses);
    }

    /**
     * 终止执行（AC-022）
     */
    @Operation(summary = "终止执行", description = "终止正在执行的工作流")
    @DeleteMapping("/executions/{executionId}")
    public Result<Void> terminate(@PathVariable String executionId) {
        executionService.terminate(executionId);
        return Result.success(null, "工作流已终止");
    }

    /**
     * 恢复执行（P3 新增，AC-017）
     * <p>
     * 业务含义：从暂停断点恢复工作流执行，返回新 SSE 流（step_skipped -> step_start...）。
     * 校验与快照恢复由 service.resume 完成：执行不存在抛 WORKFLOW_NOT_FOUND(5500)、
     * 状态非 PAUSED/快照缺失抛 WORKFLOW_NOT_RESUMABLE(5507)，经全局异常处理器转 JSON。
     * SSE 超时与 execute 端点对齐：0 = 永不超时，长任务恢复流不会被掐断。
     * </p>
     */
    @Operation(summary = "恢复执行", description = "从暂停断点恢复工作流执行，返回新 SSE 流（已完成步骤跳过）")
    @PostMapping(value = "/executions/{executionId}/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter resume(@PathVariable String executionId) {
        SseEmitter emitter = new SseEmitter(0L);
        executionService.resume(executionId, emitter);
        return emitter;
    }
}
