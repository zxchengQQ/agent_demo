package com.agentdemo.app.template;

import com.agentdemo.app.core.AgentDefinition;
import com.agentdemo.app.core.OrchestrationMode;
import com.agentdemo.app.core.ParameterDefinition;
import com.agentdemo.app.core.SupervisorDefinition;
import com.agentdemo.app.core.WorkflowTemplate;
import com.agentdemo.app.registry.WorkflowTemplateRegistry;
import com.agentdemo.common.constant.ModelConstants;
import com.agentdemo.tools.registry.ToolRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 预置工作流模板：任务拆解-执行-汇总（Supervisor 层级编排，P3 Task-16）
 * <p>
 * 业务含义：主控 Agent（强模型）将复杂任务拆解为子任务清单 -> 按主控指定路由
 * 研究/分析/总结 Worker（快模型）依次执行 -> 主控汇总为综合报告（AC-007）。
 * AC-031/BR-APP-012：主控拆解与汇总用 pro 模型保证质量，Worker 用 lite 模型控制成本。
 * </p>
 */
@Configuration
public class TaskBreakdownSupervisorTemplate {

    /** 预置模板 ID */
    public static final String TEMPLATE_ID = "task-breakdown-supervisor";

    private final ToolRegistry toolRegistry;

    public TaskBreakdownSupervisorTemplate(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * 注册"任务拆解-执行-汇总"Supervisor 模板
     *
     * @param registry 模板注册中心
     * @return 注册的模板对象
     */
    @Bean
    public WorkflowTemplate taskBreakdownSupervisorWorkflow(WorkflowTemplateRegistry registry) {
        WorkflowTemplate template = WorkflowTemplate.builder()
                .id(TEMPLATE_ID)
                .name("任务拆解-执行-汇总")
                .description("Supervisor 层级工作流：主控 Agent 拆解复杂任务为子任务清单，"
                        + "调度研究/分析/总结 Worker 依次执行，最终汇总为综合报告")
                .mode(OrchestrationMode.SUPERVISOR)
                .maxRetries(3)
                .agents(List.of())
                .parameters(List.of(
                        ParameterDefinition.builder()
                                .name("task")
                                .type("string")
                                .required(true)
                                .description("待拆解执行的复杂任务")
                                .build()
                ))
                .supervisor(SupervisorDefinition.builder()
                        // AC-031：主控拆解用强模型（拆解质量决定编排质量），glm-5.2 需在系统 LLM 模型配置中存在
                        .planAgent(AgentDefinition.builder()
                                .name("任务拆解(主控)")
                                .description("将复杂任务拆解为子任务 JSON 清单")
                                .modelId(ModelConstants.MODEL_GLM_52)
                                .toolIds(List.of())
                                .roleName("general")
                                .scenarioName("app-supervisor-plan")
                                .interfaceClass(SupervisorPlanAgent.class)
                                .hitlEnabled(true)
                                .build())
                        // Worker 池：复用 P1 三个 Agent 接口。
                        // AC-031 原语义为 Worker 用 lite 快模型控成本，当前环境仅配置 glm-5.2，
                        // 统一使用 glm-5.2（模板引用的模型必须在 LLM 配置中存在，否则构建报 WORKFLOW_MODEL_NOT_FOUND）
                        .workers(List.of(
                                AgentDefinition.builder()
                                        .name("研究")
                                        .description("负责信息检索与事实收集")
                                        .modelId(ModelConstants.MODEL_GLM_52)
                                        .toolIds(List.of("builtin:httpGet"))
                                        .roleName("general")
                                        .scenarioName("app-research")
                                        .interfaceClass(ResearchAgent.class)
                                        .hitlEnabled(true)
                                        .build(),
                                AgentDefinition.builder()
                                        .name("分析")
                                        .description("负责数据对比与逻辑推理")
                                        .modelId(ModelConstants.MODEL_GLM_52)
                                        .toolIds(List.of())
                                        .roleName("general")
                                        .scenarioName("app-analysis")
                                        .interfaceClass(AnalysisAgent.class)
                                        .hitlEnabled(true)
                                        .build(),
                                AgentDefinition.builder()
                                        .name("总结")
                                        .description("负责内容归纳与报告撰写")
                                        .modelId(ModelConstants.MODEL_GLM_52)
                                        .toolIds(List.of())
                                        .roleName("general")
                                        .scenarioName("app-summary")
                                        .interfaceClass(SummaryAgent.class)
                                        .hitlEnabled(true)
                                        .build()
                        ))
                        // AC-031：主控汇总用强模型（综合报告质量），与拆解主控同为 glm-5.2
                        .summarizeAgent(AgentDefinition.builder()
                                .name("综合汇总(主控)")
                                .description("将各 Worker 子任务结果汇总为综合报告")
                                .modelId(ModelConstants.MODEL_GLM_52)
                                .toolIds(List.of())
                                .roleName("general")
                                .scenarioName("app-supervisor-summarize")
                                .interfaceClass(SupervisorSummarizeAgent.class)
                                .hitlEnabled(true)
                                .build())
                        .maxSubtasks(5)
                        .build())
                .build();

        registry.register(template);
        return template;
    }
}
