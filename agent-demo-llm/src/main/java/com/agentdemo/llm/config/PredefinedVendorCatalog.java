package com.agentdemo.llm.config;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 预定义厂商目录
 * <p>
 * 业务含义：维护系统内置的 LLM 厂商配置模板列表，为前端"添加厂商"页面提供选项。
 * 用户选择预定义厂商后，系统根据模板创建 LlmVendorConfig 实例，用户只需填入 API Key。
 * 预定义厂商包含：火山引擎方舟(ark)、阿里百炼(bailian)、OpenAI、DeepSeek、Ollama(本地)。
 * </p>
 */
@Component
public class PredefinedVendorCatalog {

    /**
     * 预定义厂商列表（不可变，系统启动时初始化）
     */
    private static final List<PredefinedVendor> PREDEFINED_VENDORS = Collections.unmodifiableList(Arrays.asList(
        // 火山引擎方舟 - 豆包系列模型，思考模式通过请求体触发
        new PredefinedVendor(
            "ark",
            "火山引擎方舟",
            "https://ark.cn-beijing.volces.com/api/coding/v3",
            "enabled",
            Arrays.asList(
                new PredefinedModel("doubao-seed-2.0-pro", "豆包Seed 2.0 Pro", "chat", false),
                new PredefinedModel("doubao-seed-2.0-code", "豆包Seed 2.0 Code", "chat", false),
                new PredefinedModel("doubao-seed-2.0-lite", "豆包Seed 2.0 Lite", "chat", false),
                new PredefinedModel("doubao-vision-pro", "豆包Vision Pro", "chat", true),
                new PredefinedModel("doubao-embedding-large-text-240915", "豆包Embedding Large Text", "embedding", false)
            )
        ),
        // 阿里百炼 - 多厂商聚合平台，思考模式通过模型名触发
        new PredefinedVendor(
            "bailian",
            "阿里百炼",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "none",
            Arrays.asList(
                new PredefinedModel("deepseek-v4-flash", "DeepSeek V4 Flash", "chat", false),
                new PredefinedModel("glm-5.2", "GLM 5.2", "chat", false),
                new PredefinedModel("qwen3.7-plus", "通义千问 3.7 Plus", "chat", true),
                new PredefinedModel("text-embedding-v4", "文本向量化 V4", "embedding", false)
            )
        ),
        // OpenAI - GPT 系列
        new PredefinedVendor(
            "openai",
            "OpenAI",
            "https://api.openai.com/v1",
            "none",
            Arrays.asList(
                new PredefinedModel("gpt-4o", "GPT-4o", "chat", true),
                new PredefinedModel("gpt-4o-mini", "GPT-4o Mini", "chat", false),
                new PredefinedModel("text-embedding-3-small", "Text Embedding 3 Small", "embedding", false)
            )
        ),
        // DeepSeek - 推理模型
        new PredefinedVendor(
            "deepseek",
            "DeepSeek",
            "https://api.deepseek.com/v1",
            "none",
            Arrays.asList(
                new PredefinedModel("deepseek-chat", "DeepSeek Chat", "chat", false),
                new PredefinedModel("deepseek-reasoner", "DeepSeek Reasoner", "chat", false)
            )
        ),
        // Ollama - 本地部署，用户自行安装模型
        new PredefinedVendor(
            "ollama",
            "Ollama (本地)",
            "http://localhost:11434/v1",
            "none",
            Collections.emptyList()
        )
    ));

    /**
     * 获取所有预定义厂商列表
     *
     * @return 不可变的预定义厂商列表
     */
    public List<PredefinedVendor> getPredefinedVendors() {
        return PREDEFINED_VENDORS;
    }
}
