package com.agentdemo.app.template;

import com.agentdemo.app.core.AgentDefinition;
import com.agentdemo.app.core.OrchestrationMode;
import com.agentdemo.app.core.ParallelGroup;
import com.agentdemo.app.core.ParameterDefinition;
import com.agentdemo.app.core.WorkflowTemplate;
import com.agentdemo.app.registry.WorkflowTemplateRegistry;
import com.agentdemo.tools.registry.ToolRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 预置工作流模板：多角度审查（并行）
 * <p>
 * 业务含义：安全/性能/风格三个审查 Agent 分组并行执行，各自输出独立审查报告，
 * 最终汇总为多角度综合审查报告（AC-004）。
 * </p>
 */
@Configuration
public class MultiPerspectiveReviewTemplate {

    /** 预置模板 ID */
    public static final String TEMPLATE_ID = "multi-perspective-review";

    private final ToolRegistry toolRegistry;

    public MultiPerspectiveReviewTemplate(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * 注册"多角度审查"并行模板
     *
     * @param registry 模板注册中心
     * @return 注册的模板对象
     */
    @Bean
    public WorkflowTemplate multiPerspectiveReviewWorkflow(WorkflowTemplateRegistry registry) {
        WorkflowTemplate template = WorkflowTemplate.builder()
                .id(TEMPLATE_ID)
                .name("多角度审查")
                .description("并行工作流：安全/性能/风格三个审查分组同时执行，汇总为综合审查报告")
                .mode(OrchestrationMode.PARALLEL)
                .maxRetries(3)
                .agents(List.of())
                .parameters(List.of(
                        ParameterDefinition.builder()
                                .name("content")
                                .type("string")
                                .required(true)
                                .description("待审查的技术方案或代码")
                                .build()
                ))
                .parallelGroups(List.of(
                        // 业务含义：三个分组并行执行，组间独立互不干扰（AC-004）
                        ParallelGroup.builder()
                                .name("安全审查")
                                .agents(List.of(
                                        AgentDefinition.builder()
                                                .name("安全审查 Agent")
                                                .description("从安全角度审查内容")
                                                .modelId(null)
                                                .toolIds(List.of())
                                                .roleName("general")
                                                .scenarioName("app-security-review")
                                                .interfaceClass(SecurityReviewAgent.class)
                                                .build()
                                ))
                                .build(),
                        ParallelGroup.builder()
                                .name("性能审查")
                                .agents(List.of(
                                        AgentDefinition.builder()
                                                .name("性能审查 Agent")
                                                .description("从性能角度审查内容")
                                                .modelId(null)
                                                .toolIds(List.of())
                                                .roleName("general")
                                                .scenarioName("app-performance-review")
                                                .interfaceClass(PerformanceReviewAgent.class)
                                                .build()
                                ))
                                .build(),
                        ParallelGroup.builder()
                                .name("风格审查")
                                .agents(List.of(
                                        AgentDefinition.builder()
                                                .name("风格审查 Agent")
                                                .description("从代码风格与可维护性角度审查内容")
                                                .modelId(null)
                                                .toolIds(List.of())
                                                .roleName("general")
                                                .scenarioName("app-style-review")
                                                .interfaceClass(StyleReviewAgent.class)
                                                .build()
                                ))
                                .build()
                ))
                .build();

        registry.register(template);
        return template;
    }
}
