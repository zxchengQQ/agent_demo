package com.agentdemo.llm.config;

import com.agentdemo.llm.provider.LlmServiceProvider;

/**
 * LLM 提供商枚举
 * <p>
 * 业务含义：通过配置项 llm.provider 选择当前使用的 LLM 提供商，
 * 支持 ark（火山引擎方舟）和 bailian（阿里百炼）两种取值。
 * 默认值为 ark（向后兼容）。
 * </p>
 * <p>
 * CR-002 变更：新增 {@link #code} 字段，用于与
 * {@link LlmServiceProvider#getProviderCode()}
 * 匹配，使编排层（ModelFactory）能通过注册表路由到对应厂商策略实现（对应 AC-018/AC-019）。
 * </p>
 */
public enum LlmProvider {

    /**
     * 火山引擎方舟
     */
    ARK("ark"),

    /**
     * 阿里百炼
     */
    BAILIAN("bailian");

    /**
     * 厂商代码
     * <p>
     * 业务含义：与 {@link LlmServiceProvider#getProviderCode()} 返回值匹配，
     * 用于编排层注册表查找。值需与 {@code application.yml} 中 {@code llm.provider} 配置值一致。
     * </p>
     */
    private final String code;

    LlmProvider(String code) {
        this.code = code;
    }

    /**
     * 获取厂商代码
     *
     * @return 厂商代码（如 "ark"、"bailian"）
     */
    public String getCode() {
        return code;
    }
}
