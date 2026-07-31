package com.agentdemo.llm.config;

/**
 * LLM 提供商枚举
 * <p>
 * 业务含义：通过配置项 llm.provider 选择当前使用的 LLM 提供商，
 * 支持 ark（火山引擎方舟）和 bailian（阿里百炼）两种取值。
 * 默认值为 ark（向后兼容）。
 * </p>
 */
public enum LlmProvider {
    ARK,
    BAILIAN
}