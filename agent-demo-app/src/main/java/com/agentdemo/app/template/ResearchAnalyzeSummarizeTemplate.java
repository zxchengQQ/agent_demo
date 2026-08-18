package com.agentdemo.app.template;

import com.agentdemo.app.core.AgentDefinition;
import com.agentdemo.app.core.OrchestrationMode;
import com.agentdemo.app.core.ParameterDefinition;
import com.agentdemo.app.core.WorkflowTemplate;
import com.agentdemo.app.registry.WorkflowTemplateRegistry;
import com.agentdemo.tools.registry.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 预置工作流模板：研究-分析-总结
 * <p>
 * 业务含义：定义串行工作流"研究 Agent 收集信息 → 分析 Agent 提取洞察 → 总结 Agent 生成报告"，
 * 在 Spring 启动时通过 @Bean 自动注册到 WorkflowTemplateRegistry（AC-024）。
 * 工具在模板中预定义（AC-025），工具未注册时记录 WARNING 但不阻塞启动（AC-019）。
 * </p>
 */
@Configuration
public class ResearchAnalyzeSummarizeTemplate {

    private static final Logger log = LoggerFactory.getLogger(ResearchAnalyzeSummarizeTemplate.class);

    /** 预置模板 ID */
    public static final String TEMPLATE_ID = "research-analyze-summarize";

    private final ToolRegistry toolRegistry;

    public ResearchAnalyzeSummarizeTemplate(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * 注册"研究-分析-总结"预置模板
     *
     * @param registry 模板注册中心
     * @return 注册的模板对象
     */
    @Bean
    public WorkflowTemplate researchAnalyzeSummarizeWorkflow(WorkflowTemplateRegistry registry) {
        WorkflowTemplate template = WorkflowTemplate.builder()
                .id(TEMPLATE_ID)
                .name("研究-分析-总结")
                .description("串行工作流：研究 Agent 收集信息 → 分析 Agent 提取洞察 → 总结 Agent 生成报告")
                .mode(OrchestrationMode.SEQUENTIAL)
                .maxRetries(3)
                .agents(List.of(
                        AgentDefinition.builder()
                                .name("研究 Agent")
                                .description("负责收集主题相关的研究信息")
                                .modelId(null) // 使用默认模型（AC-028）
                                .toolIds(List.of("builtin:httpGet"))
                                .roleName("general")
                                .scenarioName("app-research")
                                .interfaceClass(ResearchAgent.class)
                                .build(),
                        AgentDefinition.builder()
                                .name("分析 Agent")
                                .description("分析研究内容，提取关键洞察")
                                .modelId(null)
                                .toolIds(List.of())
                                .roleName("general")
                                .scenarioName("app-analysis")
                                .interfaceClass(AnalysisAgent.class)
                                .build(),
                        AgentDefinition.builder()
                                .name("总结 Agent")
                                .description("将分析结果总结为报告")
                                .modelId(null)
                                .toolIds(List.of())
                                .roleName("general")
                                .scenarioName("app-summary")
                                .interfaceClass(SummaryAgent.class)
                                .build()
                ))
                .parameters(List.of(
                        ParameterDefinition.builder()
                                .name("topic")
                                .type("string")
                                .required(true)
                                .description("研究主题")
                                .build()
                ))
                .build();

        // 注册到注册中心（AC-024 启动时自动注册）
        registry.register(template);
        // 工具不存在时记录 WARNING，不阻塞启动（AC-019）
        logToolWarnings(template);
        return template;
    }

    /**
     * 校验模板引用的工具是否已注册（AC-019）
     * <p>
     * 业务含义：模板预定义的工具若未在 ToolRegistry 注册，仅记录 WARNING 日志，
     * 不阻断模板注册与启动。Agent 实际构建时由 AgenticAgentFactory 处理。
     * </p>
     */
    private void logToolWarnings(WorkflowTemplate template) {
        for (AgentDefinition agent : template.getAgents()) {
            for (String toolId : agent.getToolIds()) {
                try {
                    toolRegistry.resolveTools(List.of(toolId));
                } catch (Exception e) {
                    log.warn("模板 [{}] 中 Agent [{}] 引用的工具 [{}] 未注册，已跳过（原因: {}）",
                            template.getId(), agent.getName(), toolId, e.getMessage());
                }
            }
        }
    }
}
