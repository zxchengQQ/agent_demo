package com.agentdemo.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 记忆类型枚举
 * <p>
 * 业务含义：标识 Agent 记忆的存储层级，不同层级有不同的保留策略与检索方式。
 * 三级记忆架构：短期 -> 中期 -> 长期，逐步从会话内上下文沉淀到跨会话知识。
 * </p>
 */
@Getter
@AllArgsConstructor
public enum MemoryType {

    /**
     * 短期记忆：基于消息窗口的会话内上下文，默认保留 20 条最近消息
     */
    SHORT_TERM("SHORT_TERM", "短期记忆"),

    /**
     * 中期记忆：对短期记忆的摘要压缩，用于超长对话场景
     */
    MID_TERM("MID_TERM", "中期摘要"),

    /**
     * 长期记忆：向量化的跨会话记忆，支持语义检索
     */
    LONG_TERM("LONG_TERM", "长期向量记忆");

    private final String code;
    private final String desc;
}
