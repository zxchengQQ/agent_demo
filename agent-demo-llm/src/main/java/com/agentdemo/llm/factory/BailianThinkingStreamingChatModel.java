package com.agentdemo.llm.factory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
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
 * 阿里百炼思考流式对话模型（CR-001 Task-11 新增）
 * <p>
 * 业务含义：通过原生 HTTP 直连阿里百炼 OpenAI 兼容协议端点（/compatible-mode/v1），
 * 解析 SSE 流中的 reasoning_content（推理内容）和 content（正式回复），
 * 为阿里百炼提供商提供与火山方舟一致的深度思考能力。
 * </p>
 * <p>
 * 与方舟实现的关键差异（CR-001）：
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
 * <p>
 * 实现策略：复用与方舟相同的 SSE 解析逻辑（参考 ArkThinkingStreamingChatModel），
 * 通过 protected 方法暴露以便测试 spy。请求体构建仅去除 thinking 字段。
 * </p>
 */
public class BailianThinkingStreamingChatModel implements ThinkingStreamingChatModel {

    private static final Logger log = LoggerFactory.getLogger(BailianThinkingStreamingChatModel.class);

    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final Duration timeout;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 业务含义：缓存本轮流式调用中百炼返回的 Token 用量
    // 每次流式开始时重置为 null，parseSseLine 解析到 usage 字段时更新，onComplete 时透传给 handler
    private TokenUsage capturedUsage;

    public BailianThinkingStreamingChatModel(String baseUrl, String apiKey, String modelName, Duration timeout) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.timeout = timeout;
    }

    /**
     * 构建请求体 JSON（不含 thinking 字段）
     * <p>
     * 业务含义：组装百炼 OpenAI 兼容协议的请求体。CR-001 关键差异：百炼 DeepSeek 模型通过模型名称自身触发思考能力，
     * 不需要在请求体中显式设置 thinking.type=enabled（与方舟实现的核心区别）。
     * </p>
     * <p>
     * 当 toolsJson 非空时，将其解析为 JSON 并添加到请求体的 tools 字段，使 LLM 可以在推理过程中决定调用工具。
     * </p>
     *
     * @param messages  消息列表
     * @param toolsJson 工具 JSON Schema 字符串（OpenAI 兼容格式），null 或空字符串表示不传 tools
     * @return 请求体 JSON 字符串
     */
    public String buildRequestBody(List<ChatMessage> messages, String toolsJson) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", modelName);
        root.put("stream", true);

        // 业务含义：开启 stream_options.include_usage，让百炼在流式响应中返回 usage 字段
        // 用于 Token 用量统计
        ObjectNode streamOptions = objectMapper.createObjectNode();
        streamOptions.put("include_usage", true);
        root.set("stream_options", streamOptions);

        // 业务含义：与方舟的关键差异——百炼 DeepSeek 模型不需要 thinking.type=enabled 字段
        // 模型名称（如 deepseek-v4-flash）本身就触发思考能力，模型会在响应中返回 reasoning_content

        // 转换消息列表为百炼 OpenAI 兼容协议格式
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
        root.set("messages", messagesArray);

        // 业务含义：当 toolsJson 非空时，将其添加到请求体的 tools 字段，使 LLM 可以调用工具
        if (toolsJson != null && !toolsJson.isEmpty()) {
            try {
                JsonNode toolsNode = objectMapper.readTree(toolsJson);
                root.set("tools", toolsNode);
            } catch (Exception e) {
                log.warn("解析 tools JSON 失败，忽略 tools 参数: {}", e.getMessage());
            }
        }

        return root.toString();
    }

    /**
     * 解析 SSE 单行（protected 便于测试 spy）
     * <p>
     * 业务含义：逐行解析百炼 SSE 流，提取 reasoning_content / content / tool_calls / finish_reason，
     * 分别触发对应回调。与方舟实现使用相同逻辑，因为百炼 OpenAI 兼容协议的 SSE 格式与方舟一致。
     * </p>
     *
     * @param line                 SSE 单行文本（如 "data: {...}"）
     * @param handler              回调处理器
     * @param fullResponse         累积的完整正式回复（跨行维护，用于 onComplete 时传递）
     * @param toolCallAccumulator  工具调用累积器（按 index 拼接分片）
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

            // 业务含义：解析 usage 字段
            // 开启 stream_options.include_usage 后，百炼在流式响应中携带 usage 字段
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

            // 业务含义：推理内容片段（百炼 DeepSeek 模型返回的扩展字段）
            // 百炼 OpenAI 兼容协议的字段名与方舟一致：reasoning_content
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

            // 业务含义：工具调用片段累积（与方舟一致的逻辑）
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
            log.warn("解析百炼 SSE 数据行失败: data={}", data, e);
        }
    }

    /**
     * 完整流式调用（HTTP 请求 + SSE 解析） - 单轮模式（不带工具调用）
     * <p>
     * 业务含义：调用百炼 API 发送消息，读取完整 SSE 响应文本，解析并通过 handler 回调。
     * 适用于 SimpleAgent.chatThinkingStream 等单轮深度思考场景。
     * </p>
     *
     * @param messages 消息列表
     * @param handler  回调处理器
     */
    @Override
    public void stream(List<ChatMessage> messages, ThinkingStreamHandler handler) {
        try {
            // 业务含义：单轮模式不传 toolsJson
            String requestBody = buildRequestBody(messages, null);
            String sseText = fetchSseText(requestBody);
            parseSseResponse(sseText, handler);
        } catch (Exception e) {
            log.error("百炼思考流式调用异常: model={}", modelName, e);
            handler.onError(e);
        }
    }

    /**
     * 完整流式调用（支持 tools 参数，ReAct 模式）
     * <p>
     * 业务含义：与单轮模式类似，但请求体包含 tools 字段。逐行实时读取 SSE 流并解析。
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
            fetchAndParseSseStream(requestBody, handler);
        } catch (Exception e) {
            log.error("百炼 ReAct 思考流式调用异常: model={}", modelName, e);
            handler.onError(e);
        }
    }

    /**
     * 解析完整 SSE 响应文本
     * <p>
     * 业务含义：百炼 SSE 文本按行切分，逐行委托给 parseSseLine 解析。
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
     * 逐行读取 HTTP SSE 流并实时解析（protected 便于测试 spy）
     * <p>
     * 业务含义：发起 HTTP 请求获取百炼 SSE 流，使用 BufferedReader 逐行读取，
     * 每读到一行 data: {...} 立即调用 parseSseLine 解析并回调 handler。
     * </p>
     *
     * @param requestBody 请求体 JSON
     * @param handler     回调处理器
     */
    protected void fetchAndParseSseStream(String requestBody, ThinkingStreamHandler handler) {
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
                throw new RuntimeException("百炼 API 返回非 200 状态码: " + responseCode);
            }

            // 业务含义：逐行实时读取 SSE 流
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
            throw new RuntimeException("调用百炼 API 失败: " + e.getMessage(), e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 执行 HTTP 调用获取完整 SSE 响应文本（protected 便于测试 spy）
     * 业务含义：实际 HTTP 调用百炼 Chat Completions API，返回完整 SSE 文本
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
                throw new RuntimeException("百炼 API 返回非 200 状态码: " + responseCode);
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
            throw new RuntimeException("调用百炼 API 失败: " + e.getMessage(), e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getModelName() {
        return modelName;
    }
}
