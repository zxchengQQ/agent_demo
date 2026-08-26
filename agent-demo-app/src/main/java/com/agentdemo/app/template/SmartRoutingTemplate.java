package com.agentdemo.app.template;

import com.agentdemo.app.core.AgentDefinition;
import com.agentdemo.app.core.BranchDefinition;
import com.agentdemo.app.core.OrchestrationMode;
import com.agentdemo.app.core.ParameterDefinition;
import com.agentdemo.app.core.WorkflowContext;
import com.agentdemo.app.core.WorkflowTemplate;
import com.agentdemo.app.registry.WorkflowTemplateRegistry;
import com.agentdemo.tools.registry.ToolRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 预置工作流模板：智能路由（条件分支）
 * <p>
 * 业务含义：根据问题复杂度自动路由——简单问题直接回答，复杂问题走研究→分析→总结多 Agent 协作（AC-005）。
 * 条件谓词读取共享上下文 question 参数判断复杂度。
 * </p>
 */
@Configuration
public class SmartRoutingTemplate {

    /** 预置模板 ID */
    public static final String TEMPLATE_ID = "smart-routing";

    /** 简单/复杂问题判定阈值（字符数） */
    private static final int SIMPLE_QUESTION_MAX_LENGTH = 80;

    /** 判定为"复杂问题"的关键词（覆盖多 Agent 协作/调研/报告类任务意图） */
    private static final String COMPLEX_KEYWORDS =
            "(为什么|如何|怎么|分析|对比|方案|设计|调研|总结|报告|综述|规划|制定|评估|研究|讨论|阐述|解释)";

    /** 任务引导前缀：以这些词开头的请求视为任务请求（应走复杂拆解，即使较短） */
    private static final String TASK_PREFIX = "^(帮我|请|麻烦|请你|我想请你|帮我进行|请帮我|请给我).*";

    private final ToolRegistry toolRegistry;

    public SmartRoutingTemplate(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * 注册"智能路由"条件分支模板
     *
     * @param registry 模板注册中心
     * @return 注册的模板对象
     */
    @Bean
    public WorkflowTemplate smartRoutingWorkflow(WorkflowTemplateRegistry registry) {
        WorkflowTemplate template = WorkflowTemplate.builder()
                .id(TEMPLATE_ID)
                .name("智能路由")
                .description("条件分支工作流：根据问题复杂度路由到简单回答或复杂拆解分支")
                .mode(OrchestrationMode.CONDITIONAL)
                .maxRetries(3)
                .agents(List.of())
                .parameters(List.of(
                        ParameterDefinition.builder()
                                .name("question")
                                .type("string")
                                .required(true)
                                .description("用户问题")
                                .build()
                ))
                .branches(List.of(
                        // 业务含义：短问题且无复杂关键词 → 简单回答分支（AC-005）
                        BranchDefinition.builder()
                                .name("简单回答")
                                .conditionDescription("问题简短（≤80 字符）且无需多步拆解")
                                .condition(this::isSimpleQuestion)
                                .agents(List.of(
                                        AgentDefinition.builder()
                                                .name("简单回答 Agent")
                                                .description("直接回答简单问题")
                                                .modelId(null)
                                                .toolIds(List.of())
                                                .roleName("general")
                                                .scenarioName("app-quick-answer")
                                                .interfaceClass(QuickAnswerAgent.class)
                                                .hitlEnabled(true)
                                                .build()
                                ))
                                .build(),
                        // 业务含义：长问题或含复杂关键词 → 复杂拆解分支（研究→分析→总结）（AC-005）
                        BranchDefinition.builder()
                                .name("复杂拆解")
                                .conditionDescription("问题复杂，需研究-分析-总结多 Agent 拆解")
                                .condition(this::isComplexQuestion)
                                .agents(List.of(
                                        AgentDefinition.builder()
                                                .name("研究 Agent")
                                                .description("收集主题相关研究信息")
                                                .modelId(null)
                                                .toolIds(List.of("builtin:httpGet"))
                                                .roleName("general")
                                                .scenarioName("app-research")
                                                .interfaceClass(ResearchAgent.class)
                                                .hitlEnabled(true)
                                                .build(),
                                        AgentDefinition.builder()
                                                .name("分析 Agent")
                                                .description("分析研究内容提取洞察")
                                                .modelId(null)
                                                .toolIds(List.of())
                                                .roleName("general")
                                                .scenarioName("app-analysis")
                                                .interfaceClass(AnalysisAgent.class)
                                                .hitlEnabled(true)
                                                .build(),
                                        AgentDefinition.builder()
                                                .name("总结 Agent")
                                                .description("将分析结果总结为报告")
                                                .modelId(null)
                                                .toolIds(List.of())
                                                .roleName("general")
                                                .scenarioName("app-summary")
                                                .interfaceClass(SummaryAgent.class)
                                                .hitlEnabled(true)
                                                .build()
                                ))
                                .build()
                ))
                .build();

        registry.register(template);
        return template;
    }

    /**
     * 判断是否为简单问题：长度 ≤ 阈值，不含复杂关键词，且不是任务引导请求
     * <p>
     * 业务含义：只有"短、无复杂意图、非任务请求"的问题才走简单回答分支；
     * 带"帮我/请"前缀或含调研/总结等任务词的问题一律走复杂拆解（BUG 修复：扩充任务意图识别）。
     * </p>
     */
    private boolean isSimpleQuestion(WorkflowContext ctx) {
        String question = ctx.readAsString("question");
        if (question.matches(TASK_PREFIX)) {
            return false; // 任务引导请求 → 复杂
        }
        return question.length() <= SIMPLE_QUESTION_MAX_LENGTH
                && !question.matches(".*" + COMPLEX_KEYWORDS + ".*");
    }

    /**
     * 判断是否为复杂问题：长度 > 阈值，或含复杂关键词，或是任务引导请求
     */
    private boolean isComplexQuestion(WorkflowContext ctx) {
        String question = ctx.readAsString("question");
        return !isSimpleQuestion(ctx);
    }
}
