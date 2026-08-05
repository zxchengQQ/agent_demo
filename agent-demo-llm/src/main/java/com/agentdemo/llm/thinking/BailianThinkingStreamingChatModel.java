package com.agentdemo.llm.thinking;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;

/**
 * 阿里百炼思考流式模型（CR-002 Task-23 改造为继承抽象基类）
 * <p>
 * 业务含义：通过原生 HTTP 直连阿里百炼 OpenAI 兼容协议端点（/compatible-mode/v1），
 * 解析 SSE 流中的 reasoning_content（推理内容）和 content（正式回复），
 * 为阿里百炼提供商提供与火山方舟一致的深度思考能力。
 * </p>
 * <p>
 * CR-002 Task-23：继承 {@link AbstractThinkingStreamingChatModel}，仅实现 {@link #customizeRequestBody} 钩子。
 * 通用的请求体编排、SSE 解析、HTTP 调用、回调分发、消息转换、tools 处理逻辑由基类提供
 * （对应 AC-020，代码重复率从 95% 降至 ≤ 30%）。
 * </p>
 * <p>
 * 与火山方舟实现的关键差异：
 * <ul>
 *   <li>请求体不包含 thinking.type=enabled 字段——阿里百炼 DeepSeek 模型通过模型名称自身触发思考能力，无需额外参数</li>
 *   <li>Base URL 使用阿里百炼的 OpenAI 兼容协议地址（/compatible-mode/v1）</li>
 *   <li>API Key 来自 BailianProperties</li>
 *   <li>SSE 流格式与方舟兼容，reasoning_content 字段名一致</li>
 * </ul>
 * </p>
 * <p>
 * 约束：
 * <ul>
 *   <li>遵循 BR-LLM-010：Base URL 使用 OpenAI 兼容协议地址</li>
 *   <li>遵循 BR-LLM-009：API Key 从 BailianProperties 注入，禁止硬编码</li>
 *   <li>遵循 BR-LLM-004：模型实例缓存复用（由 ModelFactory 管理）</li>
 *   <li>遵循 BR-LLM-014：阿里百炼模式下支持深度思考模式</li>
 * </ul>
 * </p>
 */
public class BailianThinkingStreamingChatModel extends AbstractThinkingStreamingChatModel {

    public BailianThinkingStreamingChatModel(String baseUrl, String apiKey, String modelName, Duration timeout) {
        super(baseUrl, apiKey, modelName, timeout);
    }

    /**
     * 阿里百炼差异化钩子：空实现（不添加 thinking 字段）
     * <p>
     * 业务含义：与火山方舟的关键差异——百炼 DeepSeek 模型不需要 thinking.type=enabled 字段，
     * 模型名称（如 deepseek-v4-flash）本身就触发思考能力，模型会在响应中返回 reasoning_content。
     * 因此本钩子为空实现，请求体仅包含基类构建的通用信封（model/stream/stream_options/messages/tools）。
     * </p>
     *
     * @param root 请求体 ObjectNode（已包含 model/stream/stream_options 基础信封）
     */
    @Override
    protected void customizeRequestBody(ObjectNode root) {
        // 百炼无需差异化字段，模型名称自身触发思考能力
    }
}
