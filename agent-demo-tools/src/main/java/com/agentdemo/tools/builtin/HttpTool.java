package com.agentdemo.tools.builtin;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.tools.permission.DefaultToolPermission;
import com.agentdemo.tools.permission.ToolPermissionLevel;
import com.agentdemo.tools.sanitize.SanitizeContext;
import com.agentdemo.tools.sanitize.ToolOutputSanitizer;
import com.agentdemo.tools.sanitize.ToolSanitizeProperties;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * HTTP 请求工具
 * <p>
 * 业务含义：提供 HTTP GET/POST 请求能力，支持联网搜索场景。
 * 安全措施（防御链）：① URL 协议白名单（仅 http/https，AC-S01）；
 * ② SSRF 内网防护（AC-S08 保持）；③ 响应 MIME 白名单（非白名单类型不返回原文，AC-S02）；
 * ④ HTML 可执行内容剥离 + 可疑指令清洗 + 字数限制（经 ToolOutputSanitizer，AC-S03~S06）。
 * </p>
 */
@Component
@DefaultToolPermission(ToolPermissionLevel.ASK)
public class HttpTool {

    private static final Logger log = LoggerFactory.getLogger(HttpTool.class);

    private final ToolOutputSanitizer sanitizer;
    private final ToolSanitizeProperties sanitizeProperties;
    private final RestTemplate restTemplate;

    /**
     * 无参构造（测试/回退场景）：默认使用直通清洗器（不启用清洗）
     */
    public HttpTool() {
        this(ToolOutputSanitizer.disabled(), new ToolSanitizeProperties(), createRestTemplate());
    }

    /**
     * 真实构造（Spring 注入）：清洗链路生效。@Autowired 明确指定 Spring 在多构造器下
     * 使用本构造注入依赖（否则 Spring 默认走无参构造，导致清洗直通不生效）。
     */
    @Autowired
    public HttpTool(ToolOutputSanitizer sanitizer, ToolSanitizeProperties sanitizeProperties, RestTemplate restTemplate) {
        this.sanitizer = sanitizer;
        this.sanitizeProperties = sanitizeProperties;
        this.restTemplate = restTemplate;
    }

    private static RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(30).toMillis());
        return new RestTemplate(factory);
    }

    /**
     * 发起 HTTP GET 请求
     * 业务含义：Agent 可调用此工具获取网页内容或 API 响应，响应经清洗后返回
     *
     * @param url 请求 URL（仅 http/https）
     * @return 清洗后的响应内容（超长时前缀 + 临时文件指引）
     * @throws BusinessException 协议非法/SSRF 拦截/请求失败时抛出
     */
    @Tool("发起 HTTP GET 请求获取网页或 API 内容，参数 url 为完整 URL")
    public String httpGet(String url) {
        validateUrl(url);
        log.info("HTTP GET 请求: {}", url);
        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, null, String.class);
            return sanitizeResponse(response, "httpGet");
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.TOOL_EXECUTION_FAILED,
                    "HTTP GET 请求失败: " + url, e);
        }
    }

    /**
     * 发起 HTTP POST 请求
     *
     * @param url  请求 URL（仅 http/https）
     * @param body 请求体（JSON 字符串）
     * @return 清洗后的响应内容
     * @throws BusinessException 协议非法/SSRF 拦截/请求失败时抛出
     */
    @Tool("发起 HTTP POST 请求向 API 提交数据。"
            + "适用场景：需要向服务器发送数据（如表单提交、API 调用）时调用。"
            + "不适用场景：仅需要获取网页内容时用 httpGet。"
            + "参数 url 为完整 URL，body 为 JSON 格式字符串请求体。"
            + "返回响应正文（超长自动截断并指引分段读取）。仅支持 http/https 协议，禁止访问内网地址，违规时返回错误。")
    public String httpPost(String url, String body) {
        validateUrl(url);
        log.info("HTTP POST 请求: {}, body 长度: {}", url, body != null ? body.length() : 0);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            return sanitizeResponse(response, "httpPost");
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.TOOL_EXECUTION_FAILED,
                    "HTTP POST 请求失败: " + url, e);
        }
    }

    /**
     * 响应清洗：MIME 白名单 -> 清洗管道（HTML 剥离/分级处置/限长/包裹声明）
     * <p>
     * 业务含义：所有允许返回的响应统一经 ToolOutputSanitizer 处理后进入上下文，
     * 使响应内容作为数据而非指令（AC-S06）。Content-Type 缺失时按 HTML 处理走剥离。
     * </p>
     */
    private String sanitizeResponse(ResponseEntity<String> response, String toolName) {
        MediaType contentType = response.getHeaders().getContentType();
        String body = response.getBody();
        if (body == null) {
            return "空响应";
        }
        if (contentType != null) {
            String mimeType = contentType.getType() + "/" + contentType.getSubtype();
            if (!matchesAllowedMimeType(mimeType)) {
                // MIME 非白名单：不返回原文，返回可读提示（AC-S02）
                String hint = "HTTP 响应 Content-Type 为 " + mimeType
                        + "，不在允许返回的文本类型白名单内，已拦截该内容。";
                return sanitizer.sanitize(hint, SanitizeContext.builder()
                        .toolName(toolName).sourceDesc("HTTP 响应类型提示").htmlContent(false).build());
            }
        }
        boolean isHtml = contentType == null || isHtmlMime(contentType);
        return sanitizer.sanitize(body, SanitizeContext.builder()
                .toolName(toolName)
                .sourceDesc(isHtml ? "网页内容" : "HTTP API 响应")
                .htmlContent(isHtml)
                .build());
    }

    /**
     * MIME 白名单匹配：精确匹配 + application/*+json 与 application/*+xml 通配
     */
    private boolean matchesAllowedMimeType(String mimeType) {
        for (String allowed : sanitizeProperties.getAllowedMimeTypes()) {
            if (allowed.equals(mimeType)) {
                return true;
            }
            if (allowed.equals("application/*+json") && mimeType.matches("application/[^/;]+\\+json")) {
                return true;
            }
            if (allowed.equals("application/*+xml") && mimeType.matches("application/[^/;]+\\+xml")) {
                return true;
            }
        }
        return false;
    }

    private boolean isHtmlMime(MediaType contentType) {
        String type = contentType.getType() + "/" + contentType.getSubtype();
        return "text/html".equals(type) || "application/xhtml+xml".equals(type);
    }

    /**
     * URL 安全校验
     * 业务含义：① 协议白名单（仅 http/https，AC-S01）；
     * ② SSRF 防护：禁止访问内网地址，防止被诱导攻击内部服务（AC-S08 保持）
     *
     * @param url 待校验的 URL
     * @throws BusinessException 协议非法或 URL 含内网地址时抛出
     */
    private void validateUrl(String url) {
        if (url == null || url.isEmpty()) {
            throw new BusinessException(ErrorCode.TOOL_PARAM_INVALID, "URL 不能为空");
        }
        String lowerUrl = url.toLowerCase();
        // 协议白名单（AC-S01）：仅允许 http/https，拒绝 file://、ftp:// 等
        if (!(lowerUrl.startsWith("http://") || lowerUrl.startsWith("https://"))) {
            throw new BusinessException(ErrorCode.TOOL_PARAM_INVALID,
                    "仅支持 http/https 协议，已拒绝请求: " + url);
        }
        for (String prefix : PRIVATE_IP_PREFIXES) {
            if (lowerUrl.contains("://" + prefix)
                    || (prefix.equals("localhost") && lowerUrl.contains("://localhost"))) {
                throw new BusinessException(ErrorCode.TOOL_PARAM_INVALID,
                        "SSRF 防护：禁止访问内网地址 " + url);
            }
        }
    }

    /**
     * 内网 IP 前缀（用于 SSRF 防护，禁止访问内网）
     */
    private static final String[] PRIVATE_IP_PREFIXES = {
            "10.", "172.16.", "172.17.", "172.18.", "172.19.", "172.20.",
            "172.21.", "172.22.", "172.23.", "172.24.", "172.25.", "172.26.",
            "172.27.", "172.28.", "172.29.", "172.30.", "172.31.", "192.168.",
            "127.", "0.0.0.0", "localhost"
    };
}
