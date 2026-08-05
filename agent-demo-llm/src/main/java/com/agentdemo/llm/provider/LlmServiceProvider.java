package com.agentdemo.llm.provider;

import com.agentdemo.llm.capability.*;
import com.agentdemo.llm.registry.ModelFactory;

/**
 * LLM 厂商策略聚合接口（CR-002 Task-17 新增，Task-24 修正）
 * <p>
 * 业务含义：聚合 LLM 厂商的核心能力接口，作为厂商策略实现的统一契约。
 * 编排层（{@link ModelFactory}）通过 {@code List<LlmServiceProvider>} 一次注入所有厂商策略，
 * 按 {@link #getProviderCode()} 路由到对应实现（对应 AC-018）。
 * </p>
 * <p>
 * 设计决策（Task-24 修正 ISP 缺陷）：
 * <ul>
 *   <li>原 Task-17 设计将 {@link VisionChatModelProvider} 也纳入聚合接口，导致所有厂商必须实现视觉能力，
 *       违反接口隔离原则（ISP），且无法验证 AC-021（能力缺失时明确报错）</li>
 *   <li>修正后：聚合接口仅包含 4 个核心能力（ChatModel/StreamingChatModel/ThinkingStreamingChatModel/EmbeddingModel），
 *       视觉能力作为可选能力，厂商通过显式 {@code implements VisionChatModelProvider} 按需实现</li>
 *   <li>编排层通过 {@code provider instanceof VisionChatModelProvider} 检测能力是否支持，
 *       未支持时抛出 {@code UnsupportedCapabilityException}（对应 AC-021）</li>
 * </ul>
 * </p>
 * <p>
 * 实现示例：
 * <pre>
 * &#64;Component
 * public class ArkLlmServiceProvider implements LlmServiceProvider, VisionChatModelProvider {
 *     &#64;Override
 *     public String getProviderCode() { return "ark"; }
 *     // ... 其他能力方法实现 ...
 * }
 * </pre>
 * </p>
 */
public interface LlmServiceProvider extends
        ChatModelProvider,
        StreamingChatModelProvider,
        ThinkingStreamingChatModelProvider,
        EmbeddingModelProvider {

    /**
     * 获取厂商代码
     * <p>
     * 业务含义：与 {@link com.agentdemo.llm.config.LlmProvider#getCode()} 匹配，
     * 用于编排层从注册表中查找当前激活的厂商策略。
     * </p>
     *
     * @return 厂商代码（如 "ark"、"bailian"）
     */
    String getProviderCode();
}
