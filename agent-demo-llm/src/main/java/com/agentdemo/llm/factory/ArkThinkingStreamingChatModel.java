package com.agentdemo.llm.factory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
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
import java.util.List;

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
     * 构建请求体 JSON（含 thinking.enabled 参数）
     * 业务含义：组装方舟 Chat Completions API 的请求体，固定开启 thinking
     *
     * @param messages 消息列表
     * @return 请求体 JSON 字符串
     */
    public String buildRequestBody(List<ChatMessage> messages) {
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

        return root.toString();
    }

    /**
     * 解析 SSE 响应文本，通过 handler 回调暴露推理内容与正式回复
     * 业务含义：逐行解析 SSE data 字段，JSON 解析 delta.reasoning_content 和 delta.content
     *
     * @param sseText 完整 SSE 响应文本（含 data: 前缀的多行）
     * @param handler 回调处理器
     */
    public void parseSseResponse(String sseText, ThinkingStreamHandler handler) {
        if (sseText == null || sseText.isEmpty()) {
            return;
        }

        StringBuilder fullResponse = new StringBuilder();

        // 按行解析 SSE 文本
        String[] lines = sseText.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("data:")) {
                continue;
            }

            // 提取 data: 后的内容
            String data = trimmed.substring(5).trim();
            if (data.isEmpty() || data.equals("[DONE]")) {
                continue;
            }

            try {
                JsonNode root = objectMapper.readTree(data);
                JsonNode choices = root.path("choices");
                if (!choices.isArray() || choices.isEmpty()) {
                    continue;
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

                // 业务含义：finish_reason=stop 表示流式完成，触发 onComplete 携带完整正式回复
                JsonNode finishReason = choice.path("finish_reason");
                if (!finishReason.isMissingNode() && !finishReason.isNull()) {
                    if ("stop".equals(finishReason.asText())) {
                        handler.onComplete(fullResponse.toString());
                    }
                }
            } catch (Exception e) {
                log.warn("解析 SSE 数据行失败: data={}", data, e);
            }
        }
    }

    /**
     * 完整流式调用（HTTP 请求 + SSE 解析）
     * 业务含义：编排 buildRequestBody -> fetchSseText -> parseSseResponse，异常时触发 onError
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
