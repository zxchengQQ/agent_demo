package com.agentdemo.agent.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PlanCommandParser 测试
 * <p>
 * 验证标准来源：unified-chat-mode 任务规划 Task-01 验证标准
 * 关联 AC：AC-N04（/plan 强制拆解前缀识别）、AC-E02（空内容判定基础）
 * </p>
 */
class PlanCommandParserTest {

    /**
     * AC-N04：消息以 /plan 开头（后跟空白）时识别为强制拆解，剥离前缀后的内容作为任务主题
     */
    @Test
    void shouldParsePlanPrefixWithContent() {
        PlanCommandParser.PlanCommand command = PlanCommandParser.parse("/plan 帮我调研竞品");

        assertTrue(command.forced(), "以 /plan 开头的消息应识别为强制拆解");
        assertEquals("帮我调研竞品", command.content(), "应剥离 /plan 前缀，保留任务主题");
    }

    /**
     * AC-E02：消息仅含 /plan（无任务内容）时 forced=true 且 content 为空（交由编排层友好提示）
     */
    @Test
    void shouldParsePlanOnlyMessageAsEmptyContent() {
        PlanCommandParser.PlanCommand command = PlanCommandParser.parse("/plan");

        assertTrue(command.forced(), "仅含 /plan 的消息应识别为强制拆解意图");
        assertEquals("", command.content(), "无任务内容时 content 应为空字符串");
    }

    /**
     * AC-E02：/plan 后仅跟空白字符时同样视为空内容
     */
    @Test
    void shouldParsePlanWithOnlyWhitespaceAsEmptyContent() {
        PlanCommandParser.PlanCommand command = PlanCommandParser.parse("/plan   ");

        assertTrue(command.forced());
        assertEquals("", command.content(), "/plan 后仅空白时 content 应为空字符串");
    }

    /**
     * 前缀后必须跟空白或串尾：/plan 紧贴文字（无空格）不识别为强制拆解指令
     */
    @Test
    void shouldNotParsePlanPrefixWithoutSeparator() {
        PlanCommandParser.PlanCommand command = PlanCommandParser.parse("/plan任务无空格");

        assertFalse(command.forced(), "/plan 紧贴文字时不应识别为强制拆解");
        assertEquals("/plan任务无空格", command.content(), "内容应原样保留");
    }

    /**
     * /plans 等以 /plan 为前缀的其他单词不误识别
     */
    @Test
    void shouldNotParseSimilarPrefixWord() {
        PlanCommandParser.PlanCommand command = PlanCommandParser.parse("/plans for tomorrow");

        assertFalse(command.forced(), "/plans 是不同单词，不应误识别为 /plan 指令");
        assertEquals("/plans for tomorrow", command.content());
    }

    /**
     * 仅识别小写 /plan，大写 /PLAN 不识别（避免误触发）
     */
    @Test
    void shouldNotParseUppercasePlan() {
        PlanCommandParser.PlanCommand command = PlanCommandParser.parse("/PLAN 大写");

        assertFalse(command.forced(), "大写 /PLAN 不应识别为强制拆解指令");
        assertEquals("/PLAN 大写", command.content());
    }

    /**
     * 普通消息不识别，内容原样返回
     */
    @Test
    void shouldParseNormalMessageAsNotForced() {
        PlanCommandParser.PlanCommand command = PlanCommandParser.parse("普通消息");

        assertFalse(command.forced(), "普通消息不应识别为强制拆解");
        assertEquals("普通消息", command.content(), "内容应原样保留");
    }

    /**
     * 消息前后空白自动 trim：前缀识别与剥离均基于 trim 后内容
     */
    @Test
    void shouldTrimWhitespaceAroundMessage() {
        PlanCommandParser.PlanCommand command = PlanCommandParser.parse("  /plan 任务  ");

        assertTrue(command.forced(), "前后空白不影响 /plan 前缀识别");
        assertEquals("任务", command.content(), "剥离前缀后应去除多余空白");
    }

    /**
     * null 消息安全处理：视为普通消息（forced=false，content 为 null）
     */
    @Test
    void shouldHandleNullMessageSafely() {
        PlanCommandParser.PlanCommand command = PlanCommandParser.parse(null);

        assertFalse(command.forced(), "null 消息不应识别为强制拆解");
        assertEquals(null, command.content());
    }
}
