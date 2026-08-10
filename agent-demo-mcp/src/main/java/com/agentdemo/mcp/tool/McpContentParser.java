package com.agentdemo.mcp.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * MCP 内容类型策略分发器（CR-002）
 * <p>
 * 业务含义：将 MCP Server 返回的原始 JSON-RPC 响应解析为 LLM 可消费的文本。
 * 按 MCP 协议定义的内容类型（text/image/audio/resource/structuredContent/unknown）
 * 策略分发到对应的处理方法，统一输出格式，解耦 LangChain4j 内部异常行为依赖。
 * </p>
 * <p>
 * 设计模式：单类方法分发（参考 Dify MCP 内容分流方案），非策略模式多类。
 * MCP 内容类型是有限集合（6 种），不会频繁新增，KISS 原则。
 * </p>
 */
@Slf4j
@Component
public class McpContentParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 解析原始 JSON-RPC 响应为 LLM 可消费的文本
     *
     * @param rawResponse 原始 JSON-RPC 响应字符串（来自 McpTransportWrapper 缓存）
     * @return 解析后的文本；无法解析时返回 null
     */
    public String parse(String rawResponse) {
        // 1. 边界检查：null/空/空白 -> 返回 null
        if (rawResponse == null || rawResponse.isBlank()) {
            return null;
        }

        // 2. JSON 解析：非法 JSON -> 返回 null，不抛异常
        JsonNode root;
        try {
            root = objectMapper.readTree(rawResponse);
        } catch (Exception e) {
            log.warn("McpContentParser: JSON 解析失败: {}", e.getMessage());
            return null;
        }

        // 3. 提取 result 节点
        JsonNode resultNode = root.path("result");
        if (resultNode.isMissingNode() || resultNode.isNull()) {
            return null;
        }

        // 4. 提取 content 数组 + isError 标记
        JsonNode contentArray = resultNode.path("content");
        boolean isError = resultNode.path("isError").asBoolean(false);

        // 5. 遍历 content[]，按 type 分发到各处理器
        StringBuilder sb = new StringBuilder();
        if (contentArray.isArray() && !contentArray.isEmpty()) {
            for (JsonNode content : contentArray) {
                String type = content.path("type").asText("");
                String processed = dispatchContent(type, content);
                if (processed != null && !processed.isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append(processed);
                }
            }
        }

        // 6. 检查 structuredContent（若有）
        JsonNode structuredContent = resultNode.path("structuredContent");
        if (!structuredContent.isMissingNode() && !structuredContent.isNull()) {
            String structured = processStructuredContent(structuredContent);
            if (structured != null && !structured.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(structured);
            }
        }

        // 7. 全部为空 -> 返回 null
        if (sb.length() == 0) {
            return null;
        }

        // 8. isError=true 时添加 [工具错误] 前缀
        if (isError) {
            return "[工具错误] " + sb.toString();
        }

        return sb.toString();
    }

    // ============ 内容类型分发 ============

    /**
     * 按 content type 分发到对应的处理方法
     */
    private String dispatchContent(String type, JsonNode content) {
        return switch (type) {
            case "text" -> processText(content);
            case "image" -> processImage(content);
            case "audio" -> processAudio(content);
            case "resource" -> processResource(content);
            default -> processUnknown(type, content);
        };
    }

    /**
     * 文本内容：直接提取 text 字段
     */
    private String processText(JsonNode content) {
        return content.path("text").asText("");
    }

    /**
     * 图片内容：URL -> Markdown 图片语法；base64 -> 文本描述
     * <p>
     * 业务含义：本项目无文件存储，只有 image URL 类型可转为 Markdown 图片语法（URL 指向外部资源）。
     * base64 图片返回文本描述（MIME 类型 + 数据摘要），因为无法将 base64 落盘生成 URL。
     * </p>
     */
    private String processImage(JsonNode content) {
        String url = content.has("url") ? content.get("url").asText() : null;
        if (url != null && !url.isEmpty()) {
            return "![图片](" + url + ")";
        }
        String mimeType = content.path("mimeType").asText("unknown");
        String data = content.has("data") ? content.get("data").asText() : "";
        return "[图片] 已生成 " + mimeType + " 格式图片，base64 数据长度: " + data.length() + " 字符";
    }

    /**
     * 音频内容：返回文本描述（对话中无法播放音频）
     */
    private String processAudio(JsonNode content) {
        String mimeType = content.path("mimeType").asText("unknown");
        String data = content.has("data") ? content.get("data").asText() : "";
        return "[音频] 已生成 " + mimeType + " 格式音频，base64 数据长度: " + data.length() + " 字符";
    }

    /**
     * 内嵌资源：TextResourceContents -> 提取文本；BlobResourceContents -> 描述
     */
    private String processResource(JsonNode content) {
        JsonNode resource = content.path("resource");
        if (resource.isMissingNode() || resource.isNull()) {
            return null;
        }
        // TextResourceContents：有 text 字段
        if (resource.has("text")) {
            return resource.get("text").asText("");
        }
        // BlobResourceContents：有 blob 字段
        if (resource.has("blob")) {
            String mimeType = resource.path("mimeType").asText("unknown");
            String blob = resource.get("blob").asText("");
            return "[资源] " + mimeType + " 格式二进制资源，base64 数据长度: " + blob.length() + " 字符";
        }
        return null;
    }

    /**
     * 结构化输出：序列化为 JSON 文本
     */
    private String processStructuredContent(JsonNode structuredContent) {
        try {
            return objectMapper.writeValueAsString(structuredContent);
        } catch (Exception e) {
            log.warn("McpContentParser: structuredContent 序列化失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 未知类型：记录 WARNING 日志，静默跳过（返回 null）
     * <p>
     * 业务含义：MCP 协议会演进新增内容类型，解析器必须对未知类型具备韧性，
     * 不得导致工具调用失败。
     * </p>
     */
    private String processUnknown(String type, JsonNode content) {
        log.warn("McpContentParser: 遇到未知内容类型 [{}]，已静默跳过", type);
        return null;
    }
}
