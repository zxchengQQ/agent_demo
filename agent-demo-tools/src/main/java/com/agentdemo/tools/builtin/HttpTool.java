package com.agentdemo.tools.builtin;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * HTTP 请求工具
 * <p>
 * 业务含义：提供 HTTP GET/POST 请求能力，支持联网搜索场景。
 * 安全措施：禁止访问内网地址（防止 SSRF），响应长度限制（防止 Token 消耗过大）。
 * </p>
 */
@Component
public class HttpTool {

    private static final Logger log = LoggerFactory.getLogger(HttpTool.class);

    /**
     * 最大响应长度（10KB，超过截断，防止消耗过多 Token）
     */
    private static final int MAX_RESPONSE_LENGTH = 10 * 1024;

    /**
     * 内网 IP 前缀（用于 SSRF 防护，禁止访问内网）
     */
    private static final String[] PRIVATE_IP_PREFIXES = {
            "10.", "172.16.", "172.17.", "172.18.", "172.19.", "172.20.",
            "172.21.", "172.22.", "172.23.", "172.24.", "172.25.", "172.26.",
            "172.27.", "172.28.", "172.29.", "172.30.", "172.31.", "192.168.",
            "127.", "0.0.0.0", "localhost"
    };

    private final RestTemplate restTemplate;

    public HttpTool() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(30).toMillis());
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 发起 HTTP GET 请求
     * 业务含义：Agent 可调用此工具获取网页内容或 API 响应
     *
     * @param url 请求 URL
     * @return 响应内容（超过 10KB 截断）
     * @throws BusinessException SSRF 防护拦截或请求失败时抛出
     */
    @Tool("发起 HTTP GET 请求获取网页或 API 内容，参数 url 为完整 URL")
    public String httpGet(String url) {
        validateUrl(url);
        log.info("HTTP GET 请求: {}", url);
        try {
            String response = restTemplate.getForObject(url, String.class);
            return truncateResponse(response);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.TOOL_EXECUTION_FAILED,
                    "HTTP GET 请求失败: " + url, e);
        }
    }

    /**
     * 发起 HTTP POST 请求
     *
     * @param url  请求 URL
     * @param body 请求体（JSON 字符串）
     * @return 响应内容
     * @throws BusinessException SSRF 防护拦截或请求失败时抛出
     */
    @Tool("发起 HTTP POST 请求，参数 url 为完整 URL，body 为 JSON 字符串请求体")
    public String httpPost(String url, String body) {
        validateUrl(url);
        log.info("HTTP POST 请求: {}, body 长度: {}", url, body != null ? body.length() : 0);
        try {
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            org.springframework.http.HttpEntity<String> entity = new org.springframework.http.HttpEntity<>(body, headers);
            String response = restTemplate.postForObject(url, entity, String.class);
            return truncateResponse(response);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.TOOL_EXECUTION_FAILED,
                    "HTTP POST 请求失败: " + url, e);
        }
    }

    /**
     * URL 安全校验
     * 业务含义：SSRF 防护，禁止 Agent 访问内网地址，防止被诱导攻击内部服务
     *
     * @param url 待校验的 URL
     * @throws BusinessException URL 包含内网地址时抛出
     */
    private void validateUrl(String url) {
        if (url == null || url.isEmpty()) {
            throw new BusinessException(ErrorCode.TOOL_PARAM_INVALID, "URL 不能为空");
        }
        String lowerUrl = url.toLowerCase();
        for (String prefix : PRIVATE_IP_PREFIXES) {
            if (lowerUrl.contains("://" + prefix) || lowerUrl.contains("://" + prefix.replace(".", "."))
                    || (prefix.equals("localhost") && lowerUrl.contains("://localhost"))) {
                throw new BusinessException(ErrorCode.TOOL_PARAM_INVALID,
                        "SSRF 防护：禁止访问内网地址 " + url);
            }
        }
    }

    /**
     * 截断过长的响应
     * 业务含义：工具返回内容过长会消耗大量 Token，截断到 10KB 并提示
     *
     * @param response 原始响应
     * @return 截断后的响应
     */
    private String truncateResponse(String response) {
        if (response == null) {
            return "空响应";
        }
        if (response.length() <= MAX_RESPONSE_LENGTH) {
            return response;
        }
        return response.substring(0, MAX_RESPONSE_LENGTH) + "\n...[响应已截断，原始长度：" + response.length() + " 字符]";
    }
}
