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
 * 火山方舟思考流式模型（CR-001 新增）
 * <p>
 * 业务含义：直连方舟 Chat Completions API（stream=true, thinking.enabled），
 * 解析 SSE 流中的 delta.reasoning_content 和 delta.content，分别通过 ThinkingStreamHandler 回调暴露。
 * </p>
 * <p>
 * 约束：
 * - 遵循 BR-LLM-002：使用 Coding Plan 专用地址 /api/coding/v3
 * - 遵循 BR-LLM-001：API Key 从 ArkProperties 注入，禁止硬编码
 * - 遵循 BR-LLM-004：模型实例缓存复用（由 ModelFactory 管理）
 * </p>
 */
public class ArkThinkingStreamingChatModel {

    private static final Logger log = LoggerFactory.getLogger(ArkThinkingStreamingChatModel.class);

    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final Duration timeout;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ArkThinkingStreamingChatModel(String baseUrl, String apiKey, String modelName, Duration timeout) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.timeout = timeout;
    }

    /**
     * 构建请求体 JSON（含 thinking.enabled 参数） - 向后兼容重载
     * <p>
     * 业务含义：委托给 {@link #buildRequestBody(List, String)}，toolsJson 传 null
     * </p>
     *
     * @param messages 消息列表
     * @return 请求体 JSON 字符串
     */
    public String buildRequestBody(List<ChatMessage> messages) {
        return buildRequestBody(messages, null);
    }

    /**
     * 构建请求体 JSON（含 thinking.enabled 参数和可选 tools 参数）
     * <p>
     * 业务含义：组装方舟 Chat Completions API 的请求体，固定开启 thinking。
     * 当 toolsJson 非空时，将其解析为 JSON 并添加到请求体的 tools 字段，
     * 使 LLM 可以在推理过程中决定调用工具（Task-06 新增）。
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

        // 业务含义：开启深度思考，让方舟返回 reasoning_content（CR-001 核心）
        ObjectNode thinking = objectMapper.createObjectNode();
        thinking.put("type", "enabled");
        root.set("thinking", thinking);

        // 转换消息列表为方舟 API 格式
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
     * 解析 SSE 响应文本，通过 handler 回调暴露推理内容与正式回复
     * <p>
     * 业务含义：逐行解析 SSE data 字段，JSON 解析 delta.reasoning_content 和 delta.content。
     * 内部委托给 {@link #parseSseLine(String, ThinkingStreamHandler, StringBuilder)} 逐行处理。
     * </p>
     *
     * @param sseText 完整 SSE 响应文本（含 data: 前缀的多行）
     * @param handler 回调处理器
     */
    public void parseSseResponse(String sseText, ThinkingStreamHandler handler) {
        if (sseText == null || sseText.isEmpty()) {
            return;
        }

        StringBuilder fullResponse = new StringBuilder();
        Map<Integer, ToolCall> toolCallAccumulator = new LinkedHashMap<>();

        // 按行解析 SSE 文本，委托给 parseSseLine
        String[] lines = sseText.split("\n");
        for (String line : lines) {
            parseSseLine(line, handler, fullResponse, toolCallAccumulator);
        }
    }

    /**
     * 解析单行 SSE data，通过 handler 回调（Task-05 新增，protected 便于测试 spy）
     * <p>
     * 业务含义：逐行实时解析方舟 SSE 流，提取 reasoning_content / content / tool_calls / finish_reason，
     * 分别触发对应回调。支持 ReAct 模式下的工具调用解析。
     * </p>
     *
     * @param line         SSE 单行文本（如 "data: {...}"）
     * @param handler      回调处理器
     * @param fullResponse 累积的完整正式回复（跨行维护，用于 onComplete 时传递）
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
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return;
            }

            JsonNode choice = choices.get(0);
            JsonNode delta = choice.path("delta");

            // 业务含义：推理内容片段（方舟扩展字段，LangChain4j openai4j 不透传）
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

            // 业务含义：工具调用片段累积（BUG 修复：tool_calls delta 分片返回，需按 index 累积拼接）
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
                handler.onComplete(fullResponse.toString(), finishReason.asText());
            }
        } catch (Exception e) {
            log.warn("解析 SSE 数据行失败: data={}", data, e);
        }
    }

    /**
     * 完整流式调用（HTTP 请求 + SSE 解析） - 向后兼容重载
     * <p>
     * 业务含义：委托给 {@link #stream(List, String, ThinkingStreamHandler)}，toolsJson 传 null。
     * 走原有 fetchSseText + parseSseResponse 路径。
     * </p>
     *
     * @param messages 消息列表
     * @param handler  回调处理器
     */
    public void stream(List<ChatMessage> messages, ThinkingStreamHandler handler) {
        try {
            String requestBody = buildRequestBody(messages);
            String sseText = fetchSseText(requestBody);
            parseSseResponse(sseText, handler);
        } catch (Exception e) {
            log.error("思考流式调用异常: model={}", modelName, e);
            handler.onError(e);
        }
    }

    /**
     * 完整流式调用（支持 tools 参数，Task-05 新增）
     * <p>
     * 业务含义：使用 tools 参数发起 ReAct 流式调用，逐行实时读取 SSE 流并解析。
     * 编排 buildRequestBody(messages, toolsJson) -> fetchAndParseSseStream。
     * 异常时触发 onError。
     * </p>
     *
     * @param messages  消息列表
     * @param toolsJson 工具 JSON Schema 字符串，null 表示不传 tools
     * @param handler   回调处理器
     */
    public void stream(List<ChatMessage> messages, String toolsJson, ThinkingStreamHandler handler) {
        try {
            String requestBody = buildRequestBody(messages, toolsJson);
            fetchAndParseSseStream(requestBody, handler);
        } catch (Exception e) {
            log.error("思考流式调用异常: model={}", modelName, e);
            handler.onError(e);
        }
    }

    /**
     * 逐行读取 HTTP SSE 流并实时解析（Task-05 新增，protected 便于测试 spy）
     * <p>
     * 业务含义：发起 HTTP 请求获取 SSE 流，使用 BufferedReader 逐行读取，
     * 每读到一行 data: {...} 立即调用 parseSseLine 解析并回调 handler。
     * 相比 fetchSseText + parseSseResponse 两步走，实现了真正的逐 Token 实时推送。
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
                throw new RuntimeException("方舟 API 返回非 200 状态码: " + responseCode);
            }

            // 业务含义：逐行实时读取 SSE 流，每行立即解析并回调（核心改造点）
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
            throw new RuntimeException("调用方舟 API 失败: " + e.getMessage(), e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 执行 HTTP 调用获取完整 SSE 响应文本（protected 便于测试 spy）
     * 业务含义：实际 HTTP 调用方舟 Chat Completions API，返回完整 SSE 文本
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

            // 写入请求体
            try (OutputStream os = connection.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                throw new RuntimeException("方舟 API 返回非 200 状态码: " + responseCode);
            }

            // 读取完整 SSE 响应
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
            throw new RuntimeException("调用方舟 API 失败: " + e.getMessage(), e);
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
