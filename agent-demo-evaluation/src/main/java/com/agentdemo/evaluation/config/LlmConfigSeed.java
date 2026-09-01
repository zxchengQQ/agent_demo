package com.agentdemo.evaluation.config;

import com.agentdemo.llm.config.LlmConfigStore;
import com.agentdemo.llm.config.LlmModelConfig;
import com.agentdemo.llm.config.LlmVendorConfig;
import org.springframework.beans.factory.InitializingBean;

import java.util.ArrayList;
import java.util.List;

/**
 * CLI 上下文 LLM 配置种子（langsmith-observability CR-002 审查补链）
 * <p>
 * 业务含义：评估 CLI 为独立上下文（组件扫描不含 web 模块），{@link LlmConfigStore}
 * 无前端配置面、初始为空，导致真实评估（AC-N10/N11）无法解析模型。本种子在
 * eval.enabled=true 且 store 为空时注入一个合成厂商（baseUrl/apiKey 经 eval.*
 * 配置注入，API Key 仅环境变量），并按 agent-model-id / judge-model-id 各注册一个
 * chat 模型（名称解析经 ModelFactory 的 id-or-name 兜底规则命中），使 CLI 可独立
 * 发起真实对话与 judge 调用。已有关厂商配置时不覆盖（尊重既有配置）。
 * </p>
 */
public class LlmConfigSeed implements InitializingBean {

    private final LlmConfigStore store;
    private final EvalProperties props;

    public LlmConfigSeed(LlmConfigStore store, EvalProperties props) {
        this.store = store;
        this.props = props;
    }

    @Override
    public void afterPropertiesSet() {
        if (!props.isEnabled()) {
            return;
        }
        if (!store.getAllVendors().isEmpty()) {
            return;
        }
        seed();
    }

    private void seed() {
        String agentModel = props.getAgentModelId().isBlank() ? "" : props.getAgentModelId().trim();
        String judgeModel = props.getJudgeModelId().isBlank() ? "" : props.getJudgeModelId().trim();
        if (agentModel.isBlank() && judgeModel.isBlank()) {
            throw new IllegalStateException(
                    "评估 CLI 未配置任何模型：请设置 --eval.agent-model-id 或 --eval.judge-model-id（CLI 配置种子前置）");
        }
        // 多源 judge：judge 配置了独立 baseUrl 且 apiKey 时，单独建厂商（不同模型家族对冲同源偏差）；
        // 否则 judge 并入 Agent 厂商（同 provider）
        boolean judgeSeparate = !judgeModel.isBlank()
                && !props.getJudgeBaseUrl().isBlank() && !props.getJudgeApiKey().isBlank();

        List<LlmModelConfig> agentModels = new ArrayList<>();
        addChatModel(agentModels, agentModel);
        if (!judgeModel.isBlank() && !judgeSeparate && !judgeModel.equals(agentModel)) {
            addChatModel(agentModels, judgeModel);
        }
        if (!agentModels.isEmpty()) {
            store.addVendor(buildVendor(props.getVendorName(), props.getBaseUrl(), props.getApiKey(), agentModels));
        }
        if (judgeSeparate) {
            List<LlmModelConfig> judgeModels = new ArrayList<>();
            addChatModel(judgeModels, judgeModel);
            store.addVendor(buildVendor(props.getJudgeVendorName(), props.getJudgeBaseUrl(), props.getJudgeApiKey(), judgeModels));
        }
    }

    private static LlmVendorConfig buildVendor(String name, String baseUrl, String apiKey, List<LlmModelConfig> models) {
        LlmVendorConfig vendor = new LlmVendorConfig();
        vendor.setName(name);
        vendor.setType("custom");
        vendor.setBaseUrl(baseUrl);
        vendor.setApiKey(apiKey);
        vendor.setThinkingTrigger("enabled");
        vendor.setModels(models);
        return vendor;
    }

    private static void addChatModel(List<LlmModelConfig> models, String modelName) {
        if (modelName.isBlank()) {
            return;
        }
        LlmModelConfig m = new LlmModelConfig();
        m.setModelName(modelName);
        m.setType("chat");
        m.setSupportsVision(false);
        models.add(m);
    }
}
