package com.agentdemo.skill.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评估数据集完整性测试（技术方案 Task-23，支撑全部 AC 评估）
 * <p>
 * 业务含义：验证 skill-eval/ 与 skill-adversarial/ 数据集可被加载且结构完整，
 * 作为 EDD（Task-24）与行为测试（Task-25/26）的数据基础。
 * </p>
 */
class SkillEvalDatasetTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private JsonNode load(String path) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(is).as("数据集应存在: " + path).isNotNull();
            return objectMapper.readTree(is);
        }
    }

    @Test
    void normalInteractionDatasetShouldBeLoadableAndComplete() throws Exception {
        JsonNode root = load("skill-eval/normal-interaction.json");
        JsonNode cases = root.get("cases");
        assertThat(cases).isNotNull();
        assertThat(cases.size()).isGreaterThanOrEqualTo(8);
        // 每条含 input + expectActivation
        for (JsonNode c : cases) {
            assertThat(c.has("input")).isTrue();
            assertThat(c.has("expectActivation")).isTrue();
        }
    }

    @Test
    void ambiguityDatasetShouldBeLoadable() throws Exception {
        JsonNode root = load("skill-eval/ambiguity.json");
        assertThat(root.get("cases").size()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void injectionAdversarialShouldCoverBothChannels() throws Exception {
        JsonNode root = load("skill-eval/injection-adversarial.json");
        List<JsonNode> cases = new java.util.ArrayList<>();
        root.get("cases").forEach(cases::add);
        assertThat(cases).hasSizeGreaterThanOrEqualTo(4);
        // 覆盖用户输入直接注入 + 工具返回间接注入双通道（AC-S03）
        assertThat(cases.stream().anyMatch(c -> "user-input".equals(c.get("channel").asText()))).isTrue();
        assertThat(cases.stream().anyMatch(c -> "tool-return".equals(c.get("channel").asText()))).isTrue();
        // 全部 expectActivation=false（注入 0 生效为通过标准）
        for (JsonNode c : cases) {
            assertThat(c.get("expectActivation").asBoolean()).isFalse();
        }
    }

    @Test
    void edgeAndMemoryDatasetsShouldBeLoadable() throws Exception {
        assertThat(load("skill-eval/edge-fallback.json").get("cases").size()).isGreaterThanOrEqualTo(4);
        assertThat(load("skill-eval/memory-context.json").get("cases").size()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void contentAdversarialShouldCoverFourCategories() throws Exception {
        JsonNode root = load("skill-adversarial/content-adversarial.json");
        List<JsonNode> cases = new java.util.ArrayList<>();
        root.get("cases").forEach(cases::add);
        assertThat(cases).hasSizeGreaterThanOrEqualTo(5);
        // 四类恶意 + 一个良性样本
        long malicious = cases.stream().filter(c -> c.get("expectBlocked").asBoolean()).count();
        assertThat(malicious).isGreaterThanOrEqualTo(4);
        assertThat(cases.stream().anyMatch(c -> !c.get("expectBlocked").asBoolean())).isTrue();
    }
}
