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
 * <p>
 * CR-002 新增：{@link #getProviderCode()} 派生方法，便于编排层（ModelFactory）
 * 直接获取当前激活的厂商代码，用于从注册表查找对应 {@code LlmServiceProvider}。
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

    /**
     * 获取当前激活的厂商代码（CR-002 新增）
     * <p>
     * 业务含义：从 {@link LlmProvider#getCode()} 派生，便于编排层注册表查找。
     * 与 {@code application.yml} 中 {@code llm.provider} 配置值一致。
     * </p>
     *
     * @return 厂商代码（如 "ark"、"bailian"）
     */
    public String getProviderCode() {
        return provider.getCode();
    }
}