package com.agentdemo.app.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并行/条件/循环数据类测试（P2 Task-02）
 * <p>
 * 业务含义：验证三种新模式（并行分组/条件分支/循环定义）的数据结构可构建、
 * 谓词可评估、WorkflowTemplate 可承载、Predicate 不参与 JSON 序列化。
 * </p>
 */
class ModeDefinitionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AgentDefinition mockAgent(String name) {
        return AgentDefinition.builder().name(name).build();
    }

    @Test
    void parallelGroup_shouldBuildWithNameAndAgents() {
        ParallelGroup group = ParallelGroup.builder()
                .name("安全审查")
                .agents(List.of(mockAgent("安全 Agent"), mockAgent("审计 Agent")))
                .build();
        assertEquals("安全审查", group.getName());
        assertEquals(2, group.getAgents().size());
        assertEquals("安全 Agent", group.getAgents().get(0).getName());
    }

    @Test
    void branchDefinition_shouldBuildWithConditionPredicate() {
        BranchDefinition trueBranch = BranchDefinition.builder()
                .name("复杂拆解")
                .conditionDescription("问题复杂，需多 Agent 拆解")
                .condition(ctx -> true)
                .agents(List.of(mockAgent("研究 Agent")))
                .build();
        assertEquals("复杂拆解", trueBranch.getName());
        assertEquals("问题复杂，需多 Agent 拆解", trueBranch.getConditionDescription());
        assertNotNull(trueBranch.getCondition());
        assertTrue(trueBranch.getCondition().test(new WorkflowContext()));

        // 返回 false 的谓词分支
        BranchDefinition falseBranch = BranchDefinition.builder()
                .name("简单回答")
                .condition(ctx -> ctx.readAsString("score").equals("high"))
                .build();
        assertFalse(falseBranch.getCondition().test(new WorkflowContext()));
    }

    @Test
    void loopDefinition_shouldBuildWithMaxIterationsAndExitCondition() {
        LoopDefinition loop = LoopDefinition.builder()
                .maxIterations(5)
                .exitConditionDescription("评分 ≥ 90 时退出")
                .exitCondition(ctx -> ctx.readAsString("score").equals("95"))
                .agents(List.of(mockAgent("评分 Agent"), mockAgent("修订 Agent")))
                .build();
        assertEquals(5, loop.getMaxIterations());
        assertEquals("评分 ≥ 90 时退出", loop.getExitConditionDescription());
        assertNotNull(loop.getExitCondition());

        WorkflowContext ctx = new WorkflowContext();
        ctx.write("score", "95");
        assertTrue(loop.getExitCondition().test(ctx));
        ctx.write("score", "80");
        assertFalse(loop.getExitCondition().test(ctx));
    }

    @Test
    void workflowTemplate_shouldCarryModeSpecificDefinitions() {
        LoopDefinition loopDef = LoopDefinition.builder().maxIterations(5).build();
        WorkflowTemplate template = WorkflowTemplate.builder()
                .id("t1")
                .name("循环模板")
                .mode(OrchestrationMode.LOOP)
                .loop(loopDef)
                .build();
        assertEquals(loopDef, template.getLoop());
        // 未设置的 parallelGroups/branches 为 null（@Builder 可空，不报错）
        assertNull(template.getParallelGroups());
        assertNull(template.getBranches());
    }

    @Test
    void branchDefinition_jsonSerialize_shouldExcludePredicate() throws Exception {
        BranchDefinition branch = BranchDefinition.builder()
                .name("复杂拆解")
                .conditionDescription("问题复杂")
                .condition(ctx -> true)
                .agents(List.of(mockAgent("研究 Agent")))
                .build();

        String json = objectMapper.writeValueAsString(branch);
        // 解析 JSON 顶层 key，Predicate 字段被 @JsonIgnore 排除
        var node = objectMapper.readTree(json);
        assertFalse(node.has("condition"), "Predicate 字段应被 @JsonIgnore 排除");
        assertFalse(node.has("conditionValue"), "Predicate 不应序列化为任何派生字段");
        // 可展示字段正常序列化
        assertEquals("复杂拆解", node.get("name").asText());
        assertEquals("问题复杂", node.get("conditionDescription").asText());
    }

    @Test
    void loopDefinition_jsonSerialize_shouldExcludeExitCondition() throws Exception {
        LoopDefinition loop = LoopDefinition.builder()
                .maxIterations(5)
                .exitConditionDescription("评分 ≥ 90 时退出")
                .exitCondition(ctx -> true)
                .agents(List.of())
                .build();

        String json = objectMapper.writeValueAsString(loop);
        var node = objectMapper.readTree(json);
        assertFalse(node.has("exitCondition"), "exitCondition 谓词应被 @JsonIgnore 排除");
        assertEquals(5, node.get("maxIterations").asInt());
        assertEquals("评分 ≥ 90 时退出", node.get("exitConditionDescription").asText());
    }
}
