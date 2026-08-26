package com.agentdemo.tools.builtin;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.tools.sanitize.HtmlContentCleaner;
import com.agentdemo.tools.sanitize.SuspiciousPatternDetector;
import com.agentdemo.tools.sanitize.ToolOutputSanitizer;
import com.agentdemo.tools.sanitize.ToolOutputTempStore;
import com.agentdemo.tools.sanitize.ToolSanitizeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HttpTool 单元测试（T07）
 * <p>
 * 验证标准来源：Task-07 验证标准
 * 业务含义：验证请求级协议白名单（AC-S01）、响应 MIME 白名单（AC-S02）、
 * HTML 可执行内容剥离触发（AC-S03）、统一包裹声明（AC-S06）、超长临时文件（AC-T01）
 * 与请求异常不经清洗管道（AC-E03）。
 * </p>
 */
class HttpToolTest {

    @TempDir
    Path tempDir;

    private ToolSanitizeProperties props;
    private RestTemplate restTemplate;
    private HttpTool tool;

    @BeforeEach
    void setUp() {
        props = new ToolSanitizeProperties();
        props.setTempDir(tempDir.resolve("data").resolve("tool-output").toString());
        String allowedDir = tempDir.resolve("data").toString();
        ToolOutputSanitizer sanitizer = new ToolOutputSanitizer(props,
                new HtmlContentCleaner(), new SuspiciousPatternDetector(props),
                new ToolOutputTempStore(props, allowedDir));
        restTemplate = mock(RestTemplate.class);
        tool = new HttpTool(sanitizer, props, restTemplate);
    }

    private void stubGet(String url, MediaType contentType, String body) {
        when(restTemplate.exchange(eq(url), eq(HttpMethod.GET), isNull(), eq(String.class)))
                .thenReturn(ResponseEntity.ok().contentType(contentType).body(body));
    }

    @Test
    void 非http_https协议被拒绝() {
        assertThrows(BusinessException.class, () -> tool.httpGet("file:///etc/passwd"),
                "file:// 协议应被拒绝（AC-S01）");
        assertThrows(BusinessException.class, () -> tool.httpGet("ftp://example.com/file"),
                "ftp:// 协议应被拒绝（AC-S01）");
    }

    @Test
    void SSRF内网地址回归拒绝() {
        assertThrows(BusinessException.class, () -> tool.httpGet("http://192.168.1.1/admin"),
                "内网地址应仍被 SSRF 防护拦截（AC-S08 回归）");
        assertThrows(BusinessException.class, () -> tool.httpGet("http://localhost:8080/api"),
                "localhost 应仍被拦截（AC-S08 回归）");
    }

    @Test
    void MIME非白名单返回提示不含原文() {
        String url = "https://example.com/image.png";
        stubGet(url, MediaType.IMAGE_PNG, "PNG二进制内容");
        String result = tool.httpGet(url);

        assertTrue(result.contains("image/png"), "提示应包含实际 Content-Type（AC-S02）");
        assertFalse(result.contains("PNG二进制内容"), "非白名单内容不应返回原文（AC-S02）");
    }

    @Test
    void text_html响应剥离脚本并包裹声明() {
        String url = "https://example.com/page";
        stubGet(url, MediaType.parseMediaType("text/html"),
                "<script>alert(1)</script><h1>标题</h1>正文内容");
        String result = tool.httpGet(url);

        assertFalse(result.contains("<script"), "script 应被剥离（AC-S03）");
        assertTrue(result.contains("正文内容"), "正文应保留");
        assertTrue(result.contains("===BEGIN_TOOL_DATA==="), "应包裹声明（AC-S06）");
        assertTrue(result.contains("httpGet"), "声明应含来源工具标识");
    }

    @Test
    void application_json响应无剥离正常包裹() {
        String url = "https://api.example.com/data";
        String body = "{\"status\":\"ok\",\"data\":[1,2,3]}";
        stubGet(url, MediaType.APPLICATION_JSON, body);
        String result = tool.httpGet(url);

        assertTrue(result.contains(body), "JSON 正文应完整保留（AC-N01）");
        assertTrue(result.contains("===BEGIN_TOOL_DATA==="), "应包裹声明（AC-S06）");
        assertTrue(result.contains("httpGet"), "声明应含来源工具标识");
    }

    @Test
    void 超长响应触发临时文件机制() throws Exception {
        props.setMaxChars(100);
        String url = "https://example.com/long";
        String body = "X".repeat(500);
        stubGet(url, MediaType.TEXT_PLAIN, body);
        String result = tool.httpGet(url);

        assertTrue(result.contains("结果过长已截断"), "应含截断声明（AC-T01）");
        assertTrue(result.contains("readFile"), "应含分段查询指引（AC-T02）");

        List<Path> files = Files.list(tempDir.resolve("data").resolve("tool-output"))
                .filter(Files::isRegularFile).toList();
        assertEquals(1, files.size(), "应落盘一个临时文件");
    }

    @Test
    void 请求异常抛BusinessException不经清洗管道() {
        String url = "https://example.com/error";
        when(restTemplate.exchange(eq(url), eq(HttpMethod.GET), isNull(), eq(String.class)))
                .thenThrow(new RuntimeException("connection timeout"));
        assertThrows(BusinessException.class, () -> tool.httpGet(url),
                "请求失败应抛 BusinessException（AC-E03 错误路径不经清洗管道）");
    }

    @Test
    void post请求同样经过清洗() {
        String url = "https://example.com/submit";
        when(restTemplate.exchange(eq(url), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":\"123\"}"));
        String result = tool.httpPost(url, "{\"name\":\"x\"}");

        assertTrue(result.contains("{\"id\":\"123\"}"), "POST 响应应保留");
        assertTrue(result.contains("httpPost"), "POST 声明应含来源工具标识（AC-S06）");
    }
}
