package com.agentdemo.evaluation.config;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.llm.config.LlmConfigStore;
import com.agentdemo.llm.config.LlmVendorConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CLI 上下文 LLM 配置种子行为测试（langsmith-observability CR-002 审查补链）
 * <p>
 * 业务含义：验证评估 CLI 独立上下文中模型配置的自动注入（真实评估前置，AC-N10/N11
 * 真实执行路径）——按 agent/judge 模型 ID 注册 chat 模型、去重、缺失即失败、
 * 已有配置不覆盖、未配置 Key 启动即失败（不静默）。
 * </p>
 */
class LlmConfigSeedTest {

    private final LlmConfigStore store = new LlmConfigStore();

    private EvalProperties props(boolean enabled, String agent, String judge, String apiKey) {
        EvalProperties p = new EvalProperties();
        p.setEnabled(enabled);
        p.setAgentModelId(agent);
        p.setJudgeModelId(judge);
        p.setApiKey(apiKey);
        return p;
    }

    @Test
    void enabledWithAgentAndJudge_seedsTwoChatModels() {
        new LlmConfigSeed(store, props(true, "doubao-pro", "judge-lite", "sk-test")).afterPropertiesSet();

        assertThat(store.getAllVendors()).hasSize(1);
        LlmVendorConfig vendor = store.getAllVendors().get(0);
        assertThat(vendor.getName()).isEqualTo("eval-cli");
        assertThat(vendor.getModels()).extracting(m -> m.getModelName())
                .containsExactlyInAnyOrder("doubao-pro", "judge-lite");
        assertThat(vendor.getModels()).allMatch(m -> "chat".equals(m.getType()));
    }

    @Test
    void onlyJudgeConfigured_seedsSingleChatModel() {
        new LlmConfigSeed(store, props(true, "", "judge-lite", "sk-test")).afterPropertiesSet();

        assertThat(store.getAllVendors()).hasSize(1);
        assertThat(store.getAllVendors().get(0).getModels())
                .extracting(m -> m.getModelName()).containsExactly("judge-lite");
        assertThat(store.getFirstChatModel().getModelName()).isEqualTo("judge-lite");
    }

    @Test
    void sameModelForAgentAndJudge_deduplicated() {
        new LlmConfigSeed(store, props(true, "doubao-pro", "doubao-pro", "sk-test")).afterPropertiesSet();

        assertThat(store.getAllVendors().get(0).getModels())
                .extracting(m -> m.getModelName()).containsExactly("doubao-pro");
    }

    @Test
    void judgeSeparateVendor_multiSourceJudge_createsTwoVendors() {
        // 业务含义：多源 judge（方法论）——judge 配置独立 baseUrl/apiKey 时单独建厂商，
        // 与被评 Agent 不同模型家族，对冲同源偏差
        EvalProperties p = props(true, "doubao-pro", "deepseek-flash", "sk-ark");
        p.setJudgeBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        p.setJudgeApiKey("sk-bailian");
        new LlmConfigSeed(store, p).afterPropertiesSet();

        assertThat(store.getAllVendors()).hasSize(2);
        // 注意：getFirstChatModel() 遍历 ConcurrentHashMap 迭代序不保证（随机 UUID 哈希），
        // 多源双厂商下"首个 chat 模型"不确定——此处不依赖迭代序，改为按模型名定位各自归属厂商
        assertThat(store.getModelByName("doubao-pro", "chat").getModelName()).isEqualTo("doubao-pro");
        assertThat(store.getModelByName("deepseek-flash", "chat").getModelName()).isEqualTo("deepseek-flash");
        assertThat(store.getModelByName("deepseek-flash", "chat").getVendorId())
                .isNotEqualTo(store.getModelByName("doubao-pro", "chat").getVendorId());
    }

    @Test
    void judgeNoSeparateProvider_fallsBackIntoSingleVendor() {
        // 业务含义：judge 未配置独立 baseUrl/apiKey 时并入 Agent 厂商（单 provider 回退）
        new LlmConfigSeed(store, props(true, "doubao-pro", "deepseek-flash", "sk-test")).afterPropertiesSet();

        assertThat(store.getAllVendors()).hasSize(1);
        assertThat(store.getAllVendors().get(0).getModels())
                .extracting(m -> m.getModelName()).containsExactlyInAnyOrder("doubao-pro", "deepseek-flash");
    }

    @Test
    void noModelConfigured_failsFast() {
        assertThatThrownBy(() -> new LlmConfigSeed(store, props(true, "", "", "sk-test")).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("agent-model-id");
    }

    @Test
    void storeAlreadyPopulated_doesNotOverride() {
        EvalProperties p = props(true, "doubao-pro", "judge-lite", "sk-test");
        store.addVendor(existingVendor());
        new LlmConfigSeed(store, p).afterPropertiesSet();

        assertThat(store.getAllVendors()).hasSize(1);
        assertThat(store.getAllVendors().get(0).getName()).isEqualTo("既有厂商");
    }

    @Test
    void disabled_doesNotSeed() {
        new LlmConfigSeed(store, props(false, "doubao-pro", "judge-lite", "sk-test")).afterPropertiesSet();

        assertThat(store.getAllVendors()).isEmpty();
    }

    @Test
    void blankApiKey_failsFast_notSilentlyUnusable() {
        // 业务含义：无 API Key 时启动即失败（addVendor 校验），避免评估运行期全用例失败难定位
        assertThatThrownBy(() -> new LlmConfigSeed(store, props(true, "doubao-pro", "judge-lite", "")).afterPropertiesSet())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("API Key");
    }

    private static LlmVendorConfig existingVendor() {
        LlmVendorConfig v = new LlmVendorConfig();
        v.setName("既有厂商");
        v.setBaseUrl("https://example.com/v1");
        v.setApiKey("sk-existing");
        return v;
    }
}
