package com.agentdemo.mcp.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * McpContentParser 内容类型策略分发器测试（CR-002）
 * <p>
 * 验证来源：Task-23 验证标准
 * 关联 AC：AC-041, AC-042, AC-043, AC-044, AC-045
 * </p>
 */
@DisplayName("McpContentParser 内容类型策略分发器测试（CR-002）")
class McpContentParserTest {

    private McpContentParser parser;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        parser = new McpContentParser();
    }

    // ==================== 辅助方法：构造 JSON-RPC 响应 ====================

    /**
     * 构造包含指定 content 数组的 JSON-RPC 响应
     */
    private String buildResponse(ObjectNode... contentItems) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("jsonrpc", "2.0");
        root.put("id", 1);
        ObjectNode result = objectMapper.createObjectNode();
        result.putArray("content").addAll(java.util.Arrays.asList(contentItems));
        root.set("result", result);
        return root.toString();
    }

    /**
     * 构造包含指定 content 数组 + isError 的 JSON-RPC 响应
     */
    private String buildResponseWithError(ObjectNode... contentItems) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("jsonrpc", "2.0");
        root.put("id", 1);
        ObjectNode result = objectMapper.createObjectNode();
        result.putArray("content").addAll(java.util.Arrays.asList(contentItems));
        result.put("isError", true);
        root.set("result", result);
        return root.toString();
    }

    /**
     * 构造包含 structuredContent 的 JSON-RPC 响应
     */
    private String buildResponseWithStructured(ObjectNode structuredContent, ObjectNode... contentItems) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("jsonrpc", "2.0");
        root.put("id", 1);
        ObjectNode result = objectMapper.createObjectNode();
        if (contentItems.length > 0) {
            result.putArray("content").addAll(java.util.Arrays.asList(contentItems));
        }
        result.set("structuredContent", structuredContent);
        root.set("result", result);
        return root.toString();
    }

    private ObjectNode textContent(String text) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "text");
        node.put("text", text);
        return node;
    }

    private ObjectNode imageUrlContent(String url) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "image");
        node.put("url", url);
        return node;
    }

    private ObjectNode imageBase64Content(String data, String mimeType) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "image");
        node.put("data", data);
        node.put("mimeType", mimeType);
        return node;
    }

    private ObjectNode audioContent(String data, String mimeType) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "audio");
        node.put("data", data);
        node.put("mimeType", mimeType);
        return node;
    }

    private ObjectNode resourceTextContent(String text) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "resource");
        ObjectNode resource = objectMapper.createObjectNode();
        resource.put("text", text);
        node.set("resource", resource);
        return node;
    }

    private ObjectNode resourceBlobContent(String blob, String mimeType) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "resource");
        ObjectNode resource = objectMapper.createObjectNode();
        resource.put("blob", blob);
        resource.put("mimeType", mimeType);
        node.set("resource", resource);
        return node;
    }

    private ObjectNode unknownContent(String type) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", type);
        node.put("data", "some-data");
        return node;
    }

    // ==================== 正常流程测试 ====================

    @Nested
    @DisplayName("正常流程：各内容类型解析")
    class NormalFlow {

        @Test
        @DisplayName("parse 含 text content 的响应 -> 返回文本内容")
        void parseTextContent() {
            String response = buildResponse(textContent("北京今天晴，25°C"));
            String result = parser.parse(response);
            assertEquals("北京今天晴，25°C", result);
        }

        @Test
        @DisplayName("parse 含 image+url 的响应 -> 返回 ![图片](url) Markdown 语法")
        void parseImageUrlContent() {
            String response = buildResponse(imageUrlContent("https://example.com/chart.png"));
            String result = parser.parse(response);
            assertEquals("![图片](https://example.com/chart.png)", result);
        }

        @Test
        @DisplayName("parse 含 image+base64 的响应 -> 返回图片描述文本")
        void parseImageBase64Content() {
            String base64Data = "iVBORw0KGgoAAAANSUhEUgAA"; // 模拟 base64 数据
            String response = buildResponse(imageBase64Content(base64Data, "image/png"));
            String result = parser.parse(response);
            assertEquals("[图片] 已生成 image/png 格式图片，base64 数据长度: " + base64Data.length() + " 字符", result);
        }

        @Test
        @DisplayName("parse 含 audio 的响应 -> 返回音频描述文本（AC-042）")
        void parseAudioContent() {
            String base64Data = "UklGRiQAAABXQVZFZm10IBAAAAABAAEA"; // 模拟 base64 音频数据
            String response = buildResponse(audioContent(base64Data, "audio/wav"));
            String result = parser.parse(response);
            assertEquals("[音频] 已生成 audio/wav 格式音频，base64 数据长度: " + base64Data.length() + " 字符", result);
        }

        @Test
        @DisplayName("parse 含 resource+text 的响应 -> 返回 resource.text 文本内容（AC-043）")
        void parseResourceTextContent() {
            String response = buildResponse(resourceTextContent("这是一段嵌入资源文本"));
            String result = parser.parse(response);
            assertEquals("这是一段嵌入资源文本", result);
        }

        @Test
        @DisplayName("parse 含 resource+blob 的响应 -> 返回资源描述文本（AC-043）")
        void parseResourceBlobContent() {
            String blobData = "JVBERi0xLjQKJcfs"; // 模拟 base64 blob 数据
            String response = buildResponse(resourceBlobContent(blobData, "application/pdf"));
            String result = parser.parse(response);
            assertEquals("[资源] application/pdf 格式二进制资源，base64 数据长度: " + blobData.length() + " 字符", result);
        }

        @Test
        @DisplayName("parse 含 structuredContent 的响应 -> 返回 JSON 文本（AC-044）")
        void parseStructuredContent() {
            ObjectNode structured = objectMapper.createObjectNode();
            structured.put("key", "value");
            structured.put("count", 42);
            String response = buildResponseWithStructured(structured);
            String result = parser.parse(response);
            assertNotNull(result);
            // 验证返回的是合法 JSON
            try {
                JsonNode parsed = objectMapper.readTree(result);
                assertEquals("value", parsed.path("key").asText());
                assertEquals(42, parsed.path("count").asInt());
            } catch (Exception e) {
                fail("structuredContent 返回值不是合法 JSON: " + result);
            }
        }
    }

    // ==================== 边界与异常测试 ====================

    @Nested
    @DisplayName("边界与异常：未知类型、混合内容、isError、边界输入")
    class EdgeCases {

        @Test
        @DisplayName("parse 含未知 type 的响应 -> 跳过未知项，已知项正常返回（AC-045）")
        void parseUnknownTypeContent() {
            String response = buildResponse(
                    textContent("正常文本"),
                    unknownContent("future_type"),
                    textContent("末尾文本")
            );
            String result = parser.parse(response);
            // 未知类型被跳过，已知类型按顺序拼接
            assertEquals("正常文本\n末尾文本", result);
        }

        @Test
        @DisplayName("parse 含混合 content 的响应 -> 按顺序拼接所有内容")
        void parseMixedContent() {
            String url = "https://example.com/diagram.png";
            String response = buildResponse(
                    textContent("图表已生成："),
                    imageUrlContent(url)
            );
            String result = parser.parse(response);
            assertEquals("图表已生成：\n![图片](" + url + ")", result);
        }

        @Test
        @DisplayName("parse isError=true 的响应 -> 返回内容前缀 [工具错误]")
        void parseIsErrorResponse() {
            String response = buildResponseWithError(textContent("参数无效"));
            String result = parser.parse(response);
            assertEquals("[工具错误] 参数无效", result);
        }

        @Test
        @DisplayName("parse null 输入 -> 返回 null")
        void parseNullInput() {
            String result = parser.parse(null);
            assertNull(result);
        }

        @Test
        @DisplayName("parse 空字符串输入 -> 返回 null")
        void parseEmptyInput() {
            String result = parser.parse("");
            assertNull(result);
        }

        @Test
        @DisplayName("parse 空白字符串输入 -> 返回 null")
        void parseBlankInput() {
            String result = parser.parse("   ");
            assertNull(result);
        }

        @Test
        @DisplayName("parse 非法 JSON -> 返回 null，不抛异常")
        void parseInvalidJson() {
            String result = parser.parse("{invalid json!!!");
            assertNull(result);
        }

        @Test
        @DisplayName("parse 缺少 result.content 的响应 -> 返回 null")
        void parseMissingContent() {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("jsonrpc", "2.0");
            root.put("id", 1);
            ObjectNode result = objectMapper.createObjectNode();
            result.put("isError", false);
            root.set("result", result);
            String response = root.toString();

            String parsed = parser.parse(response);
            assertNull(parsed);
        }

        @Test
        @DisplayName("parse result.content 为空数组 -> 返回 null")
        void parseEmptyContentArray() {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("jsonrpc", "2.0");
            root.put("id", 1);
            ObjectNode result = objectMapper.createObjectNode();
            result.putArray("content");
            root.set("result", result);
            String response = root.toString();

            String parsed = parser.parse(response);
            assertNull(parsed);
        }

        @Test
        @DisplayName("parse 含 structuredContent + content 的响应 -> 先 content 后 structuredContent 拼接")
        void parseContentAndStructuredTogether() {
            ObjectNode structured = objectMapper.createObjectNode();
            structured.put("status", "ok");
            String response = buildResponseWithStructured(structured, textContent("执行完成"));
            String result = parser.parse(response);
            assertNotNull(result);
            assertTrue(result.startsWith("执行完成"), "content 应在 structuredContent 之前");
            assertTrue(result.contains("\"status\":\"ok\"") || result.contains("\"status\": \"ok\""),
                    "应包含 structuredContent 的 JSON 文本");
        }
    }
}
