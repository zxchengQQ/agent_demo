package com.agentdemo.evaluation.eval;

import com.agentdemo.llm.config.LlmConfigStore;
import com.agentdemo.llm.config.LlmModelConfig;
import com.agentdemo.llm.registry.ModelFactory;

/**
 * judge 模型访问生产实现（langsmith-observability CR-002 Task-29）
 * <p>
 * 业务含义：经项目 LLM 模块统一出口调用 judge（复用既有模型配置与出站链路，
 * 技术方案 §12 数据隐私——judge 出境复用既有 LLM 通道）；模型名解析与
 * ModelFactory 的 id-or-name 兜底规则一致（避免同源误判/漏判）。
 * </p>
 */
public class SpringJudgeModelAccess implements JudgeModelAccess {

    private final ModelFactory modelFactory;
    private final LlmConfigStore configStore;

    public SpringJudgeModelAccess(ModelFactory modelFactory, LlmConfigStore configStore) {
        this.modelFactory = modelFactory;
        this.configStore = configStore;
    }

    @Override
    public String chat(String prompt, String modelId) {
        return modelFactory.getChatModelByModelId(modelId).chat(prompt);
    }

    @Override
    public String resolvedModelName(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return null;
        }
        LlmModelConfig m = configStore.getModel(modelId);
        if (m == null) {
            m = configStore.getModelByName(modelId, "chat");
        }
        return m == null ? null : m.getModelName();
    }

    @Override
    public String defaultAgentModelName() {
        LlmModelConfig m = configStore.getFirstChatModel();
        return m == null ? null : m.getModelName();
    }
}
