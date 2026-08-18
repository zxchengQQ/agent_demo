package com.agentdemo.app.template;

import com.agentdemo.app.core.AgentDefinition;
import com.agentdemo.app.core.LoopDefinition;
import com.agentdemo.app.core.OrchestrationMode;
import com.agentdemo.app.core.ParameterDefinition;
import com.agentdemo.app.core.WorkflowContext;
import com.agentdemo.app.core.WorkflowTemplate;
import com.agentdemo.app.registry.WorkflowTemplateRegistry;
import com.agentdemo.tools.registry.ToolRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 预置工作流模板：质量评分-修订（循环）
 * <p>
 * 业务含义：循环编排"评分 Agent 打分 → 修订 Agent 改进 → 再评分"，直到评分 ≥ 90 或达最大迭代 5 次退出（AC-006/AC-029）。
 * 退出条件从共享上下文 lastOutput 中解析评分数字判断（BR-APP-014 防死循环）。
 * </p>
 */
@Configuration
public class QualityReviewLoopTemplate {

    /** 预置模板 ID */
    public static final String TEMPLATE_ID = "quality-review-loop";

    private final ToolRegistry toolRegistry;

    public QualityReviewLoopTemplate(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * 注册"质量评分-修订"循环模板
     *
     * @param registry 模板注册中心
     * @return 注册的模板对象
     */
    @Bean
    public WorkflowTemplate qualityReviewLoopWorkflow(WorkflowTemplateRegistry registry) {
        WorkflowTemplate template = WorkflowTemplate.builder()
                .id(TEMPLATE_ID)
                .name("质量评分-修订")
                .description("循环工作流：评分 Agent 评估质量 → 修订 Agent 改进，直到评分达标或达最大迭代次数")
                .mode(OrchestrationMode.LOOP)
                .maxRetries(5)
                .agents(List.of())
                .parameters(List.of(
                        ParameterDefinition.builder()
                                .name("content")
                                .type("string")
                                .required(true)
                                .description("待打磨的初稿内容")
                                .build()
                ))
                .loop(LoopDefinition.builder()
                        .maxIterations(5) // AC-029：达上限自动退出
                        .exitConditionDescription("评分 ≥ 90 时退出")
                        // 业务含义：读取"评分 Agent"的输出（含分数），分数 ≥90 达标退出（AC-006）
                        .exitCondition(ctx -> extractScore(ctx.getOutput("评分 Agent")) >= 90)
                        .agents(List.of(
                                AgentDefinition.builder()
                                        .name("评分 Agent")
                                        .description("对内容进行质量评分（0-100）")
                                        .modelId(null)
                                        .toolIds(List.of())
                                        .roleName("general")
                                        .scenarioName("app-scoring")
                                        .interfaceClass(ScoringAgent.class)
                                        .build(),
                                AgentDefinition.builder()
                                        .name("修订 Agent")
                                        .description("根据评分意见修订改进内容")
                                        .modelId(null)
                                        .toolIds(List.of())
                                        .roleName("general")
                                        .scenarioName("app-revise")
                                        .interfaceClass(ReviseAgent.class)
                                        .build()
                        ))
                        .build())
                .build();

        registry.register(template);
        return template;
    }

    /**
     * 从文本中提取评分数字（找不到数字返回 0）
     *
     * @param text 评分文本（如"评分：95"）
     * @return 提取的分数
     */
    private int extractScore(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        Matcher matcher = Pattern.compile("(\\d{1,3})").matcher(text);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }
}
