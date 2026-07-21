package com.agentdemo.llm.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * LLM 模块配置
 * <p>
 * 业务含义：注册 ArkProperties 配置属性绑定，供 ModelFactory 注入使用。
 * </p>
 */
@Configuration
@EnableConfigurationProperties(ArkProperties.class)
public class LlmConfig {
}
