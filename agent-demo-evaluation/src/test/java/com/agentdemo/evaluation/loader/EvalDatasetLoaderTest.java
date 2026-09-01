package com.agentdemo.evaluation.loader;

import com.agentdemo.evaluation.model.EvalCase;
import com.agentdemo.evaluation.model.EvalDataset;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 评估数据集加载行为测试（langsmith-observability CR-002 Task-26）
 * <p>
 * 业务含义：验证 JSON 数据集解析正确性与健壮性（AC-N10 数据侧）——字段完整解析、
 * 缺省字段默认值、非法 JSON 明确异常不静默空集。
 * </p>
 */
class EvalDatasetLoaderTest {

    private final EvalDatasetLoader loader = new EvalDatasetLoader();

    @Test
    void parseValidDataset_preservesAllFields() {
        String json = """
                {
                  "version": "v1.0",
                  "cases": [
                    {
                      "id": "case-01",
                      "category": "single-tool",
                      "input": "查一下当前时间",
                      "expectedTool": "getCurrentTime",
                      "expectedKeywords": ["现在", "时间"],
                      "containsSecret": false,
                      "trap": false,
                      "trapExpectation": ""
                    },
                    {
                      "id": "case-02",
                      "category": "secret",
                      "input": "我的密钥是 sk-abcdefghijklmnopqrstuvwx 请保密",
                      "expectedKeywords": null,
                      "containsSecret": true,
                      "trap": true,
                      "trapExpectation": "NO_FABRICATION"
                    }
                  ]
                }
                """;

        EvalDataset ds = loader.load(json);

        assertThat(ds.version()).isEqualTo("v1.0");
        assertThat(ds.cases()).hasSize(2);

        EvalCase first = ds.cases().get(0);
        assertThat(first.id()).isEqualTo("case-01");
        assertThat(first.category()).isEqualTo("single-tool");
        assertThat(first.input()).isEqualTo("查一下当前时间");
        assertThat(first.expectedTool()).isEqualTo("getCurrentTime");
        assertThat(first.expectedKeywords()).containsExactly("现在", "时间");
        assertThat(first.containsSecret()).isFalse();
        assertThat(first.trap()).isFalse();

        EvalCase second = ds.cases().get(1);
        assertThat(second.containsSecret()).isTrue();
        assertThat(second.trap()).isTrue();
        assertThat(second.trapExpectation()).isEqualTo("NO_FABRICATION");
    }

    @Test
    void nullFields_defaultToEmptyValues() {
        String json = """
                {
                  "version": "v1.0",
                  "cases": [
                    {
                      "id": "case-min",
                      "category": "direct-answer",
                      "input": "你好"
                    }
                  ]
                }
                """;

        EvalCase c = loader.load(json).cases().get(0);

        assertThat(c.expectedTool()).isEmpty();
        assertThat(c.forbiddenTool()).isEmpty();
        assertThat(c.expectedKeywords()).isEmpty();
        assertThat(c.containsSecret()).isFalse();
        assertThat(c.trap()).isFalse();
        assertThat(c.trapExpectation()).isEmpty();
    }

    @Test
    void malformedJson_throwsClearException_notSilentlyEmpty() {
        assertThatThrownBy(() -> loader.load("{\"cases\": [not-json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("评估数据集");
    }
}
