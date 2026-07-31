package com.agentdemo.llm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM 提供商选择配置
 * <p>
 * 业务含义：绑定 application.yml 中 llm.provider 配置项，
 * 用于选择当前使用的 LLM 提供商。
 * 默认值为 ark（火山引擎方舟），以保持向后兼容。
 * </p>
 * <p>
 * 配置示例：
 * <pre>
 * llm:
 *   provider: ark    # ark | bailian
 * </pre>
 * </p>
 */
@Data
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    /**
     * LLM 提供商
     * 业务含义：指定当前使用的 LLM 提供商，支持 ark（火山引擎方舟）和 bailian（阿里百炼）
     * 默认值 ARK 确保不配置时不影响现有功能
     */
    private LlmProvider provider = LlmProvider.ARK;
}