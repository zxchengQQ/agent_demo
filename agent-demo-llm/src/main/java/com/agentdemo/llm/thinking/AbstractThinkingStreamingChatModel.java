package com.agentdemo.llm.thinking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 思考流式对话模型抽象基类（CR-002 Task-18 新增）
 * <p>
 * 业务含义：模板方法模式的抽象基类，上提火山引擎方舟和阿里百炼思考流式模型的通用逻辑：
 * <ul>
 *   <li>HTTP 调用（{@link #executeStream}、{@link #fetchSseText}）</li>
 *   <li>SSE 流解析（{@link #parseSseResponse}、{@link #parseSseLine}）</li>
 *   <li>回调分发（{@link ThinkingStreamHandler} 的 onPartialThinking/onPartialResponse/onComplete/onToolCalls/onError）</li>
 *   <li>Token 用量捕获（{@link #capturedUsage}）</li>
 * </ul>
 * </p>
 * <p>
 * 子类仅需实现 {@link #buildRequestBody(List, String)} 差异化方法：
 * <ul>
 *   <li>{@code ArkThinkingStreamingChatModel}：请求体包含 {@code thinking.type=enabled}</li>
 *   <li>{@code BailianThinkingStreamingChatModel}：不发送 thinking 字段（模型名称自身触发）</li>
 * </ul>
 * </p>
 * <p>
 * 设计决策（批判性参考多LLM提供商设计模式.md）：
 * 设计模式文档建议"能力矩阵 = 抽象基类"，但本项目各厂商思考能力差异仅在请求体一个字段，
 * 抽象基类 + 模板方法是消除重复的最佳手段；其他能力（ChatModel/EmbeddingModel 等）差异仅在配置值，
 * 抽象基类会退化为空壳，因此不采用。
 * </p>
 * <p>
 * 预期效果：子类代码量从 ~460 行降至 ~80 行，重复率从 95% 降至 ≤ 30%（对应 AC-020）。
 * </p>
 */
public abstract class AbstractThinkingStreamingChatModel implements ThinkingStreamingChatModel {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    /**
     * LLM 服务 Base URL
     */
    protected final String baseUrl;

    /**
     * API Key（从环境变量注入，禁止硬编码）
     */
    protected final String apiKey;

    /**
     * 模型名称
     */
    protected final String modelName;

    /**
     * 调用超时时间
     */
    protected final Duration timeout;

    /**
     * JSON 序列化/反序列化器
     */
    protected final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 缓存本轮流式调用中 LLM 返回的 Token 用量
     * <p>
     * 业务含义：每次流式开始时重置为 null，parseSseLine 解析到 usage 字段时更新，onComplete 时透传给 handler
     * </p>
     */
    protected TokenUsage capturedUsage;

    /**
     * 构造思考流式模型
     *
     * @param baseUrl   LLM 服务 Base URL
     * @param apiKey    API Key
     * @param modelName 模型名称
     * @param timeout   调用超时时间
     */
    protected AbstractThinkingStreamingChatModel(String baseUrl, String apiKey,
                                                 String modelName, Duration timeout) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.timeout = timeout;
    }

    // ==================== 模板方法：stream 入口 ====================

    /**
     * 单轮思考流式对话（不带工具调用）
     * <p>
     * 业务含义：模板方法，编排流程：
     * 1. 调用 {@link #buildRequestBody(List, String)} 构建请求体（toolsJson 传 null）
     * 2. 通过 {@link #fetchSseText(String)} 一次性获取完整 SSE 响应文本
     * 3. 委托给 {@link #parseSseResponse(String, ThinkingStreamHandler)} 解析并回调
     * 4. 异常时通过 {@link ThinkingStreamHandler#onError(Throwable)} 回调
     * </p>
     * <p>
     * 设计决策：单轮模式采用"两步走"（fetchSseText + parseSseResponse）而非逐行实时推送，
     * 与原 ArkThinkingStreamingChatModel / BailianThinkingStreamingChatModel 行为保持一致，
     * 适用于 SimpleAgent.chatThinkingStream 等单轮深度思考场景，简化错误处理流程。
     * </p>
     *
     * @param messages 消息列表
     * @param handler  回调处理器
     */
    @Override
    public void stream(List<ChatMessage> messages, ThinkingStreamHandler handler) {
        try {
            String requestBody = buildRequestBody(messages, null);
            String sseText = fetchSseText(requestBody);
            parseSseResponse(sseText, handler);
        } catch (Exception e) {
            log.error("思考流式调用异常: model={}", modelName, e);
            handler.onError(e);
        }
    }

    /**
     * ReAct 思考流式对话（带工具调用）
     * <p>
     * 业务含义：模板方法，将 toolsJson 透传给 {@link #buildRequestBody(List, String)}，
     * 其余流程与 {@link #stream(List, ThinkingStreamHandler)} 一致。
     * </p>
     *
     * @param messages  消息列表
     * @param toolsJson 工具 JSON Schema 字符串，null 表示不传 tools
     * @param handler   回调处理器
     */
    @Override
    public void stream(List<ChatMessage> messages, String toolsJson, ThinkingStreamHandler handler) {
        try {
            String requestBody = buildRequestBody(messages, toolsJson);
            executeStream(requestBody, handler);
        } catch (Exception e) {
            log.error("思考流式调用异常: model={}", modelName, e);
            handler.onError(e);
        }
    }

    // ==================== 模板方法：请求体构建（final，子类通过钩子差异化） ====================

    /**
     * 构建请求体 JSON - 模板方法（final，子类不应覆盖）
     * <p>
     * 业务含义：编排请求体构建的通用流程：
     * 1. {@link #buildCommonEnvelope()} 构建基础信封（model/stream/stream_options）
     * 2. {@link #customizeRequestBody(ObjectNode)} 钩子，子类添加差异化字段（如 thinking.type=enabled）
     * 3. {@link #buildMessagesArray(List)} 转换消息列表
     * 4. {@link #attachToolsIfPresent(ObjectNode, String)} 附加 tools 字段
     * </p>
     * <p>
     * 设计决策（CR-002 Task-22/Task-23）：将 buildRequestBody 上提为 final 模板方法，
     * 子类仅通过 {@link #customizeRequestBody} 钩子注入差异化字段，
     * 彻底消除子类间的代码重复（对应 AC-020，重复率从 95% 降至 ≤ 30%）。
     * </p>
     *
     * @param messages  消息列表
     * @param toolsJson 工具 JSON Schema 字符串，null 或空表示不传 tools
     * @return 请求体 JSON 字符串
     */
    protected final String buildRequestBody(List<ChatMessage> messages, String toolsJson) {
        ObjectNode root = buildCommonEnvelope();
        customizeRequestBody(root);
        root.set("messages", buildMessagesArray(messages));
        attachToolsIfPresent(root, toolsJson);
        return root.toString();
    }

    /**
     * 构建请求体 JSON - 向后兼容重载（单轮模式，不带 tools）
     * <p>
     * 业务含义：委托给 {@link #buildRequestBody(List, String)}，toolsJson 传 null。
     * </p>
     *
     * @param messages 消息列表
     * @return 请求体 JSON 字符串
     */
    public String buildRequestBody(List<ChatMessage> messages) {
        return buildRequestBody(messages, null);
    }

    /**
     * 子类差异化钩子：在请求体中添加厂商特有字段
     * <p>
     * 业务含义：子类差异化实现点。
     * - 火山引擎方舟：添加 {@code thinking.type=enabled} 字段显式触发思考
     * - 阿里百炼：空实现（模型名称自身触发思考能力，无需额外字段）
     * </p>
     * <p>
     * 钩子方法在 {@link #buildRequestBody} 中被调用，此时 root 已包含基础信封（model/stream/stream_options），
     * 但尚未设置 messages 和 tools 字段。子类仅修改 root 添加自身特有字段。
     * </p>
     *
     * @param root 请求体 ObjectNode（已包含基础信封字段）
     */
    protected abstract void customizeRequestBody(ObjectNode root);

    /**
     * 获取 Base URL（测试用，验证遵循 BR-LLM-002 / BR-LLM-010）
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * 获取模型名称（测试用）
     */
    public String getModelName() {
        return modelName;
    }

    // ==================== 通用辅助：请求体构建 ====================

    /**
     * 构建请求体通用信封（model、stream、stream_options.include_usage）
     * <p>
     * 业务含义：所有厂商共享的请求体基础字段。
     * 子类在 {@link #buildRequestBody} 中调用此方法获取基础信封后，再追加自身特有字段。
     * </p>
     * <p>
     * 设计决策：stream_options.include_usage=true 是通用能力（用于 Token 用量统计），
     * 不属于厂商差异化字段，因此上提到基类。
     * </p>
     *
     * @return 包含基础字段的 ObjectNode
     */
    protected ObjectNode buildCommonEnvelope() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", modelName);
        root.put("stream", true);

        // 业务含义：开启 stream_options.include_usage，让 API 在流式响应中返回 usage 字段
        // 用于 Token 用量统计（capturedUsage 在 onComplete 时透传给 handler）
        ObjectNode streamOptions = objectMapper.createObjectNode();
        streamOptions.put("include_usage", true);
        root.set("stream_options", streamOptions);

        return root;
    }

    /**
     * 将消息列表转换为 OpenAI 兼容协议的 messages JSON 数组
     * <p>
     * 业务含义：通用的消息转换逻辑，支持 UserMessage / AiMessage / ToolExecutionResultMessage / SystemMessage。
     * 各厂商 OpenAI 兼容协议的消息格式一致，因此上提到基类消除重复（对应 AC-020）。
     * </p>
     *
     * @param messages 消息列表
     * @return messages JSON 数组
     */
    protected ArrayNode buildMessagesArray(List<ChatMessage> messages) {
        ArrayNode messagesArray = objectMapper.createArrayNode();
        for (ChatMessage msg : messages) {
            ObjectNode msgNode = messagesArray.addObject();
            String content;
            if (msg instanceof UserMessage um) {
                msgNode.put("role", "user");
                // 业务含义：UserMessage 由 List<Content> 组成，提取文本内容（TextContent）
                content = um.contents().stream()
                        .filter(c -> c instanceof TextContent)
                        .map(c -> ((TextContent) c).text())
                        .findFirst()
                        .orElse("");
            } else if (msg instanceof AiMessage am) {
                msgNode.put("role", "assistant");
                content = am.text() != null ? am.text() : "";
                msgNode.put("content", content);

                // 业务含义：AiMessage 携带 toolExecutionRequests 时，生成 tool_calls 字段（ReAct 多轮交互必需）
                if (am.hasToolExecutionRequests()) {
                    ArrayNode toolCallsArray = objectMapper.createArrayNode();
                    for (ToolExecutionRequest req : am.toolExecutionRequests()) {
                        ObjectNode tcNode = toolCallsArray.addObject();
                        tcNode.put("id", req.id() != null ? req.id() : "");
                        tcNode.put("type", "function");
                        ObjectNode function = tcNode.putObject("function");
                        function.put("name", req.name() != null ? req.name() : "");
                        function.put("arguments", req.arguments() != null ? req.arguments() : "");
                    }
                    msgNode.set("tool_calls", toolCallsArray);
                }
            } else if (msg instanceof ToolExecutionResultMessage term) {
                // 业务含义：工具执行结果消息，对应 OpenAI 协议的 tool 角色消息（ReAct 多轮交互必需）
                msgNode.put("role", "tool");
                msgNode.put("tool_call_id", term.id() != null ? term.id() : "");
                content = term.text() != null ? term.text() : "";
            } else if (msg instanceof SystemMessage sm) {
                msgNode.put("role", "system");
                content = sm.text();
            } else {
                // 兜底：未知类型按 user 处理
                msgNode.put("role", "user");
                content = msg.toString();
            }
            msgNode.put("content", content);
        }
        return messagesArray;
    }

    /**
     * 当 toolsJson 非空时，将其解析为 JSON 并附加到请求体的 tools 字段
     * <p>
     * 业务含义：通用的工具参数处理逻辑。toolsJson 为 OpenAI 兼容格式的工具 JSON Schema 字符串，
     * 解析失败时仅记录 WARN 日志，不阻断请求（降级为不传 tools）。
     * </p>
     *
     * @param root      请求体 ObjectNode
     * @param toolsJson 工具 JSON Schema 字符串，null 或空表示不传 tools
     */
    protected void attachToolsIfPresent(ObjectNode root, String toolsJson) {
        if (toolsJson == null || toolsJson.isEmpty()) {
            return;
        }
        try {
            JsonNode toolsNode = objectMapper.readTree(toolsJson);
            root.set("tools", toolsNode);
        } catch (Exception e) {
            log.warn("解析 tools JSON 失败，忽略 tools 参数: {}", e.getMessage());
        }
    }

    // ==================== 通用实现：HTTP 调用 + SSE 解析 ====================

    /**
     * 执行 HTTP 调用并逐行实时解析 SSE 流
     * <p>
     * 业务含义：发起 HTTP 请求获取 SSE 流，使用 BufferedReader 逐行读取，
     * 每读到一行 data: {...} 立即调用 {@link #parseSseLine} 解析并回调 handler，
     * 实现真正的逐 Token 实时推送。
     * </p>
     *
     * @param requestBody 请求体 JSON
     * @param handler     回调处理器
     */
    protected void executeStream(String requestBody, ThinkingStreamHandler handler) {
        HttpURLConnection connection = null;
        try {
            String url = baseUrl + "/chat/completions";
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("Accept", "text/event-stream");
            connection.setConnectTimeout((int) timeout.toMillis());
            connection.setReadTimeout((int) timeout.toMillis());
            connection.setDoOutput(true);

            // 写入请求体
            try (OutputStream os = connection.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                throw new RuntimeException("LLM API 返回非 200 状态码: " + responseCode);
            }

            // 业务含义：每次流式开始时重置 capturedUsage，避免上一轮残留
            capturedUsage = null;
            StringBuilder fullResponse = new StringBuilder();
            Map<Integer, ToolCall> toolCallAccumulator = new LinkedHashMap<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    parseSseLine(line, handler, fullResponse, toolCallAccumulator);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("调用 LLM API 失败: " + e.getMessage(), e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 执行 HTTP 调用获取完整 SSE 响应文本
     * <p>
     * 业务含义：供单轮模式（非 ReAct）使用，一次性读取完整 SSE 文本后通过 {@link #parseSseResponse} 解析。
     * ReAct 模式（带 tools）应使用 {@link #executeStream} 逐行实时推送。
     * </p>
     *
     * @param requestBody 请求体 JSON
     * @return 完整 SSE 响应文本
     */
    protected String fetchSseText(String requestBody) {
        HttpURLConnection connection = null;
        try {
            String url = baseUrl + "/chat/completions";
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("Accept", "text/event-stream");
            connection.setConnectTimeout((int) timeout.toMillis());
            connection.setReadTimeout((int) timeout.toMillis());
            connection.setDoOutput(true);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                throw new RuntimeException("LLM API 返回非 200 状态码: " + responseCode);
            }

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line).append("\n");
                }
            }
            return response.toString();
        } catch (IOException e) {
            throw new RuntimeException("调用 LLM API 失败: " + e.getMessage(), e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 解析完整 SSE 响应文本
     * <p>
     * 业务含义：按行切分 SSE 文本，逐行委托给 {@link #parseSseLine} 解析。
     * </p>
     *
     * @param sseText 完整 SSE 响应文本
     * @param handler 回调处理器
     */
    public void parseSseResponse(String sseText, ThinkingStreamHandler handler) {
        if (sseText == null || sseText.isEmpty()) {
            return;
        }

        // 业务含义：每次流式解析开始时重置 capturedUsage，避免上一轮残留
        capturedUsage = null;

        StringBuilder fullResponse = new StringBuilder();
        Map<Integer, ToolCall> toolCallAccumulator = new LinkedHashMap<>();

        String[] lines = sseText.split("\n");
        for (String line : lines) {
            parseSseLine(line, handler, fullResponse, toolCallAccumulator);
        }
    }

    /**
     * 解析单行 SSE data，通过 handler 回调
     * <p>
     * 业务含义：逐行实时解析 SSE 流，提取以下字段并触发对应回调：
     * <ul>
     *   <li>{@code delta.reasoning_content} -&gt; {@link ThinkingStreamHandler#onPartialThinking(String)}</li>
     *   <li>{@code delta.content} -&gt; {@link ThinkingStreamHandler#onPartialResponse(String)}（同时累积到 fullResponse）</li>
     *   <li>{@code delta.tool_calls} -&gt; 按 index 累积拼接（首个 chunk 含 id+name，后续 chunk 仅 arguments 片段）</li>
     *   <li>{@code choices[0].finish_reason} -&gt; 先推送累积的 tool_calls（如有），再 {@link ThinkingStreamHandler#onComplete(String, String, TokenUsage)}</li>
     *   <li>{@code usage} -&gt; 缓存到 {@link #capturedUsage}，onComplete 时透传</li>
     * </ul>
     * </p>
     *
     * @param line                SSE 单行文本（如 "data: {...}"）
     * @param handler            回调处理器
     * @param fullResponse       累积的完整正式回复（跨行维护，用于 onComplete 时传递）
     * @param toolCallAccumulator 工具调用累积器（按 index 拼接分片）
     */
    protected void parseSseLine(String line, ThinkingStreamHandler handler, StringBuilder fullResponse,
                                Map<Integer, ToolCall> toolCallAccumulator) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("data:")) {
            return;
        }

        // 提取 data: 后的内容
        String data = trimmed.substring(5).trim();
        if (data.isEmpty() || data.equals("[DONE]")) {
            return;
        }

        try {
            JsonNode root = objectMapper.readTree(data);

            // 业务含义：解析 usage 字段（stream_options.include_usage 启用后由 API 携带）
            JsonNode usageNode = root.path("usage");
            if (!usageNode.isMissingNode() && !usageNode.isNull()) {
                int promptTokens = usageNode.path("prompt_tokens").asInt(0);
                int completionTokens = usageNode.path("completion_tokens").asInt(0);
                int totalTokens = usageNode.path("total_tokens").asInt(0);
                capturedUsage = new TokenUsage(promptTokens, completionTokens, totalTokens);
            }

            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return;
            }

            JsonNode choice = choices.get(0);
            JsonNode delta = choice.path("delta");

            // 业务含义：推理内容片段（LLM 扩展字段，LangChain4j openai4j 不透传）
            JsonNode reasoningContent = delta.path("reasoning_content");
            if (!reasoningContent.isMissingNode() && !reasoningContent.isNull()) {
                String text = reasoningContent.asText();
                if (!text.isEmpty()) {
                    handler.onPartialThinking(text);
                }
            }

            // 业务含义：正式回复片段（标准 OpenAI 协议字段）
            JsonNode content = delta.path("content");
            if (!content.isMissingNode() && !content.isNull()) {
                String text = content.asText();
                if (!text.isEmpty()) {
                    handler.onPartialResponse(text);
                    fullResponse.append(text);
                }
            }

            // 业务含义：工具调用片段累积
            // OpenAI 协议中 tool_calls 流式分片：首个 chunk 含 id+name，后续 chunk 只有 arguments 片段
            JsonNode toolCallsNode = delta.path("tool_calls");
            if (toolCallsNode.isArray() && !toolCallsNode.isEmpty()) {
                for (JsonNode tcNode : toolCallsNode) {
                    int index = tcNode.path("index").asInt(0);
                    ToolCall existing = toolCallAccumulator.computeIfAbsent(index, k -> new ToolCall());
                    // id 仅在首个 chunk 出现
                    String id = tcNode.path("id").asText("");
                    if (!id.isEmpty()) {
                        existing.setId(id);
                    }
                    JsonNode function = tcNode.path("function");
                    // function.name 仅在首个 chunk 出现
                    String name = function.path("name").asText("");
                    if (!name.isEmpty()) {
                        existing.setFunctionName(name);
                    }
                    // arguments 分片返回，需追加拼接
                    String args = function.path("arguments").asText("");
                    String current = existing.getArguments();
                    existing.setArguments(current == null ? args : current + args);
                }
            }

            // 业务含义：finish_reason 非空表示流式完成，先推送累积的完整 tool_calls（如有），再触发 onComplete
            JsonNode finishReason = choice.path("finish_reason");
            if (!finishReason.isMissingNode() && !finishReason.isNull()) {
                if (!toolCallAccumulator.isEmpty()) {
                    handler.onToolCalls(new ArrayList<>(toolCallAccumulator.values()));
                }
                // 业务含义：透传 capturedUsage，API 返回时非 null，未返回时为 null
                handler.onComplete(fullResponse.toString(), finishReason.asText(), capturedUsage);
            }
        } catch (Exception e) {
            log.warn("解析 SSE 数据行失败: data={}", data, e);
        }
    }
}
