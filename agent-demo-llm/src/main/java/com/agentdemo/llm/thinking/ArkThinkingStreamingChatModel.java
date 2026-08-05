package com.agentdemo.llm.thinking;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;

/**
 * 火山方舟思考流式模型（CR-002 Task-22 改造为继承抽象基类）
 * <p>
 * 业务含义：直连方舟 Chat Completions API（stream=true, thinking.enabled），
 * 解析 SSE 流中的 delta.reasoning_content 和 delta.content，分别通过 ThinkingStreamHandler 回调暴露。
 * </p>
 * <p>
 * CR-002 Task-22：继承 {@link AbstractThinkingStreamingChatModel}，仅实现 {@link #customizeRequestBody} 钩子，
 * 添加火山方舟特有的 {@code thinking.type=enabled} 字段。
 * 通用的请求体编排、SSE 解析、HTTP 调用、回调分发、消息转换、tools 处理逻辑由基类提供
 * （对应 AC-020，代码重复率从 95% 降至 ≤ 30%）。
 * </p>
 * <p>
 * 约束：
 * - 遵循 BR-LLM-002：使用 Coding Plan 专用地址 /api/coding/v3
 * - 遵循 BR-LLM-001：API Key 从 ArkProperties 注入，禁止硬编码
 * - 遵循 BR-LLM-004：模型实例缓存复用（由 ModelFactory 管理）
 * </p>
 */
public class ArkThinkingStreamingChatModel extends AbstractThinkingStreamingChatModel {

    public ArkThinkingStreamingChatModel(String baseUrl, String apiKey, String modelName, Duration timeout) {
        super(baseUrl, apiKey, modelName, timeout);
    }

    /**
     * 火山方舟差异化钩子：添加 thinking.type=enabled 字段
     * <p>
     * 业务含义：开启深度思考，让方舟返回 reasoning_content（火山方舟差异化字段）。
     * 与阿里百炼的关键差异：方舟需要显式设置 thinking.type=enabled 触发思考能力。
     * </p>
     *
     * @param root 请求体 ObjectNode（已包含 model/stream/stream_options 基础信封）
     */
    @Override
    protected void customizeRequestBody(ObjectNode root) {
        ObjectNode thinking = objectMapper.createObjectNode();
        thinking.put("type", "enabled");
        root.set("thinking", thinking);
    }
}
