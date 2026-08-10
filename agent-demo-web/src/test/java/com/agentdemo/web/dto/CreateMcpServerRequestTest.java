package com.agentdemo.web.dto;

import com.agentdemo.mcp.config.McpTransportType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CreateMcpServerRequest DTO 校验测试
 * <p>
 * 验证标准来源：Task-15 验证标准
 * 关联 AC：AC-023, AC-024, AC-028
 * </p>
 */
class CreateMcpServerRequestTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    /** 创建合法的 stdio 请求 */
    private CreateMcpServerRequest validStdioRequest() {
        CreateMcpServerRequest req = new CreateMcpServerRequest();
        req.setName("weather");
        req.setTransport(McpTransportType.STDIO);
        req.setCommand("node");
        return req;
    }

    /** 创建合法的 sse 请求 */
    private CreateMcpServerRequest validSseRequest() {
        CreateMcpServerRequest req = new CreateMcpServerRequest();
        req.setName("fetch");
        req.setTransport(McpTransportType.SSE);
        req.setUrl("https://example.com/sse");
        return req;
    }

    private boolean hasViolationOnField(Set<ConstraintViolation<CreateMcpServerRequest>> violations, String field) {
        return violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals(field));
    }

    // ==================== name 字段校验 ====================

    @Nested
    @DisplayName("name 字段校验")
    class NameValidationTest {

        @Test
        @DisplayName("name='abc@def' 含非法字符时校验失败")
        void nameWithIllegalChars_shouldFail() {
            CreateMcpServerRequest req = validStdioRequest();
            req.setName("abc@def");
            Set<ConstraintViolation<CreateMcpServerRequest>> violations = validator.validate(req);
            assertFalse(violations.isEmpty(), "含非法字符的 name 应校验失败");
            assertTrue(hasViolationOnField(violations, "name"));
        }

        @Test
        @DisplayName("name='' 空字符串时校验失败")
        void nameEmpty_shouldFail() {
            CreateMcpServerRequest req = validStdioRequest();
            req.setName("");
            Set<ConstraintViolation<CreateMcpServerRequest>> violations = validator.validate(req);
            assertFalse(violations.isEmpty(), "空 name 应校验失败");
            assertTrue(hasViolationOnField(violations, "name"));
        }

        @Test
        @DisplayName("name 长度 > 50 时校验失败")
        void nameTooLong_shouldFail() {
            CreateMcpServerRequest req = validStdioRequest();
            req.setName("a".repeat(51));
            Set<ConstraintViolation<CreateMcpServerRequest>> violations = validator.validate(req);
            assertFalse(violations.isEmpty(), "超长 name 应校验失败");
            assertTrue(hasViolationOnField(violations, "name"));
        }

        @Test
        @DisplayName("name='weather' 合法时校验通过")
        void nameValid_shouldPass() {
            CreateMcpServerRequest req = validStdioRequest();
            Set<ConstraintViolation<CreateMcpServerRequest>> violations = validator.validate(req);
            assertTrue(violations.isEmpty(), "合法 name 应校验通过, 违规: " + violations);
        }
    }

    // ==================== transport 字段校验 ====================

    @Test
    @DisplayName("transport=null 时校验失败")
    void transportNull_shouldFail() {
        CreateMcpServerRequest req = validStdioRequest();
        req.setTransport(null);
        Set<ConstraintViolation<CreateMcpServerRequest>> violations = validator.validate(req);
        assertTrue(hasViolationOnField(violations, "transport"));
    }

    // ==================== 自定义校验（transport 条件校验） ====================

    @Nested
    @DisplayName("transport 条件校验（command/url）")
    class ConditionalValidationTest {

        @Test
        @DisplayName("transport=STDIO 且 command 为空时校验失败")
        void stdioWithoutCommand_shouldFail() {
            CreateMcpServerRequest req = validStdioRequest();
            req.setCommand(null);
            Set<ConstraintViolation<CreateMcpServerRequest>> violations = validator.validate(req);
            assertFalse(violations.isEmpty(), "stdio 无 command 应校验失败");
        }

        @Test
        @DisplayName("transport=STDIO 且 command 为空白时校验失败")
        void stdioWithBlankCommand_shouldFail() {
            CreateMcpServerRequest req = validStdioRequest();
            req.setCommand("   ");
            Set<ConstraintViolation<CreateMcpServerRequest>> violations = validator.validate(req);
            assertFalse(violations.isEmpty(), "stdio 空白 command 应校验失败");
        }

        @Test
        @DisplayName("transport=SSE 且 url='ftp://invalid' 时校验失败")
        void sseWithInvalidUrl_shouldFail() {
            CreateMcpServerRequest req = validSseRequest();
            req.setUrl("ftp://invalid");
            Set<ConstraintViolation<CreateMcpServerRequest>> violations = validator.validate(req);
            assertFalse(violations.isEmpty(), "sse 非法 url 应校验失败");
        }

        @Test
        @DisplayName("transport=SSE 且 url='https://example.com' 时校验通过")
        void sseWithValidHttpsUrl_shouldPass() {
            CreateMcpServerRequest req = validSseRequest();
            Set<ConstraintViolation<CreateMcpServerRequest>> violations = validator.validate(req);
            assertTrue(violations.isEmpty(), "合法 sse 请求应校验通过, 违规: " + violations);
        }

        @Test
        @DisplayName("transport=SSE 且 url='http://localhost:8080' 时校验通过")
        void sseWithValidHttpUrl_shouldPass() {
            CreateMcpServerRequest req = validSseRequest();
            req.setUrl("http://localhost:8080");
            Set<ConstraintViolation<CreateMcpServerRequest>> violations = validator.validate(req);
            assertTrue(violations.isEmpty(), "合法 http url 应校验通过, 违规: " + violations);
        }

        @Test
        @DisplayName("transport=SSE 且 url 为空时校验失败")
        void sseWithoutUrl_shouldFail() {
            CreateMcpServerRequest req = validSseRequest();
            req.setUrl(null);
            Set<ConstraintViolation<CreateMcpServerRequest>> violations = validator.validate(req);
            assertFalse(violations.isEmpty(), "sse 无 url 应校验失败");
        }
    }
}
