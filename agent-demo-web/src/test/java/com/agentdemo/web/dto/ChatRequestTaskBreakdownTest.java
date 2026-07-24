package com.agentdemo.web.dto;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChatRequest 任务拆解字段测试
 * <p>
 * 验证标准来源：Task-02 验证标准
 * 关联 AC：AC-001（开启拆解发送复杂任务）、AC-012（拆解开关状态保持）
 * </p>
 */
class ChatRequestTaskBreakdownTest {

    private static Validator validator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    /**
     * 验证 new ChatRequest() 实例化后 getEnableTaskBreakdown() 返回 false（默认值）
     * 业务含义：enableTaskBreakdown 默认 false，不开启拆解时行为与现有一致
     */
    @Test
    void enableTaskBreakdownShouldDefaultToFalse() {
        ChatRequest request = new ChatRequest();

        assertEquals(false, request.getEnableTaskBreakdown(),
                "enableTaskBreakdown 默认值应为 false");
    }

    /**
     * 验证通过 JSON 反序列化 {"message":"hi","enableTaskBreakdown":true} 后字段为 true
     * 业务含义：前端发送 enableTaskBreakdown=true 时后端能正确接收
     */
    @Test
    void enableTaskBreakdownShouldBeTrueWhenDeserializedFromJson() throws Exception {
        String json = "{\"message\":\"hi\",\"enableTaskBreakdown\":true}";

        ChatRequest request = objectMapper.readValue(json, ChatRequest.class);

        assertTrue(request.getEnableTaskBreakdown(), "JSON 中 enableTaskBreakdown=true 时应为 true");
    }

    /**
     * 验证 JSON 不含 enableTaskBreakdown 字段时 getEnableTaskBreakdown() 返回 false
     * 业务含义：不传字段时默认 false，Boolean.TRUE.equals(false) 为 false，null 安全
     */
    @Test
    void enableTaskBreakdownShouldBeFalseWhenJsonOmitsField() throws Exception {
        String json = "{\"message\":\"hi\"}";

        ChatRequest request = objectMapper.readValue(json, ChatRequest.class);

        assertFalse(request.getEnableTaskBreakdown(),
                "JSON 不含 enableTaskBreakdown 时应为 false（默认值）");
    }

    /**
     * 验证 enableTaskBreakdown 可通过 setter 设置
     */
    @Test
    void enableTaskBreakdownShouldBeAccessibleViaSetter() {
        ChatRequest request = new ChatRequest();
        request.setEnableTaskBreakdown(true);

        assertTrue(request.getEnableTaskBreakdown(), "setEnableTaskBreakdown(true) 后应为 true");
    }

    /**
     * 验证 enableTaskBreakdown=false 可通过 JSON 反序列化
     */
    @Test
    void enableTaskBreakdownShouldBeFalseWhenDeserializedAsFalse() throws Exception {
        String json = "{\"message\":\"hi\",\"enableTaskBreakdown\":false}";

        ChatRequest request = objectMapper.readValue(json, ChatRequest.class);

        assertFalse(request.getEnableTaskBreakdown(), "JSON 中 enableTaskBreakdown=false 时应为 false");
    }

    /**
     * 验证现有 enableThinking 字段不受影响（向后兼容）
     */
    @Test
    void enableThinkingShouldNotBeAffected() {
        ChatRequest request = new ChatRequest();

        assertNull(request.getEnableThinking(), "enableThinking 默认值应仍为 null");
    }

    /**
     * 验证 enableTaskBreakdown 和 enableThinking 可同时为 true（模式共存，AC-011）
     */
    @Test
    void bothFlagsCanBeTrueSimultaneously() {
        ChatRequest request = new ChatRequest();
        request.setEnableTaskBreakdown(true);
        request.setEnableThinking(true);

        assertTrue(request.getEnableTaskBreakdown(), "enableTaskBreakdown 应为 true");
        assertTrue(request.getEnableThinking(), "enableThinking 应为 true");
    }
}
