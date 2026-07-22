package com.agentdemo.web.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChatRequest 消息长度校验测试
 * <p>
 * 验证标准来源：T-03 验证标准
 * 关联 AC：AC-015（超长消息拦截）
 * </p>
 */
class ChatRequestTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    /**
     * 验证超过 4000 字符的消息被拒绝
     * 业务含义：防止超长消息导致 Token 浪费或系统卡顿
     */
    @Test
    void shouldRejectMessageLongerThan4000Chars() {
        ChatRequest request = new ChatRequest();
        request.setMessage("a".repeat(4001));

        Set<ConstraintViolation<ChatRequest>> violations = validator.validate(request);

        assertFalse(violations.isEmpty(), "4001 字符的消息应被拒绝");
        assertTrue(violations.stream().anyMatch(v -> v.getMessage().contains("4000")),
                "应包含长度限制提示，实际: " + violations);
    }

    /**
     * 验证正好 4000 字符的消息通过校验
     * 业务含义：边界值正好等于上限应放行
     */
    @Test
    void shouldAcceptMessageExactly4000Chars() {
        ChatRequest request = new ChatRequest();
        request.setMessage("a".repeat(4000));

        Set<ConstraintViolation<ChatRequest>> violations = validator.validate(request);

        assertTrue(violations.isEmpty(), "正好 4000 字符的消息应通过校验，实际: " + violations);
    }

    /**
     * 验证空字符串仍由 @NotBlank 拦截（@Size 与 @NotBlank 共存）
     */
    @Test
    void shouldRejectEmptyMessage() {
        ChatRequest request = new ChatRequest();
        request.setMessage("");

        Set<ConstraintViolation<ChatRequest>> violations = validator.validate(request);

        assertFalse(violations.isEmpty(), "空消息应被 @NotBlank 拦截");
    }

    // ========== CR-001 新增：enableThinking 字段测试 ==========

    /**
     * 验证标准 2：不传 enableThinking 时字段为 null
     * 业务含义：enableThinking 为可选字段，默认 null（Controller 兜底为 false）
     */
    @Test
    void enableThinkingShouldBeNullByDefault() {
        ChatRequest request = new ChatRequest();

        assertNull(request.getEnableThinking(), "不传 enableThinking 时应为 null");
    }

    /**
     * 验证标准 3：传 enableThinking=true 时字段为 true
     * 业务含义：前端开启深度思考开关时携带 enableThinking=true
     */
    @Test
    void enableThinkingShouldBeTrueWhenSet() {
        ChatRequest request = new ChatRequest();
        request.setEnableThinking(true);

        assertTrue(request.getEnableThinking(), "传 true 时应为 true");
    }

    /**
     * 验证标准 1：ChatRequest 包含 enableThinking 字段（通过 setter/getter 可访问）
     * 业务含义：DTO 必须支持 enableThinking 字段的读写
     */
    @Test
    void enableThinkingShouldBeAccessibleViaSetter() {
        ChatRequest request = new ChatRequest();
        request.setEnableThinking(false);

        assertFalse(request.getEnableThinking(), "setEnableThinking(false) 后应为 false");
    }
}
