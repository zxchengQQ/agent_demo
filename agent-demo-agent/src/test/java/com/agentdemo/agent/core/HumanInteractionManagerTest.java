package com.agentdemo.agent.core;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HumanInteractionManager 单元测试
 * <p>
 * 验证标准来源：Task-01 验证标准
 * 业务含义：验证 HITL 暂停状态管理的核心功能--保存/加载/清理/超时清理/追问计数。
 * </p>
 */
class HumanInteractionManagerTest {

    private HumanInteractionManager manager;

    @BeforeEach
    void setUp() {
        manager = new HumanInteractionManager();
    }

    @Test
    void saveInteraction_后_hasPending_返回_true() {
        List<ChatMessage> messages = List.of(
                SystemMessage.from("系统提示词"),
                UserMessage.from("用户消息")
        );
        manager.saveInteraction("sess-001", messages, "text", "请提供订单号",
                null, 0, "model-001", "{}");

        assertTrue(manager.hasPending("sess-001"));
    }

    @Test
    void loadInteraction_返回保存的完整状态() {
        List<ChatMessage> messages = List.of(
                SystemMessage.from("系统提示词"),
                UserMessage.from("帮我查订单")
        );
        manager.saveInteraction("sess-002", messages, "confirm", "确认删除文件XXX？",
                Arrays.asList("确认", "取消"), 1, "model-002", "{\"tools\":[]}");

        PendingInteraction pending = manager.loadInteraction("sess-002");

        assertNotNull(pending);
        assertEquals("confirm", pending.getAskUserType());
        assertEquals("确认删除文件XXX？", pending.getQuestion());
        assertEquals(Arrays.asList("确认", "取消"), pending.getOptions());
        assertEquals(1, pending.getRetryCount());
        assertEquals("model-002", pending.getModelId());
        assertEquals("{\"tools\":[]}", pending.getToolsJson());
        assertEquals(2, pending.getMessages().size());
    }

    @Test
    void clearInteraction_后_hasPending_返回_false() {
        manager.saveInteraction("sess-003", List.of(UserMessage.from("test")),
                "text", "问题", null, 0, "model", "{}");

        assertTrue(manager.hasPending("sess-003"));
        manager.clearInteraction("sess-003");
        assertFalse(manager.hasPending("sess-003"));
    }

    @Test
    void 同一sessionId_重复save_覆盖旧状态() {
        manager.saveInteraction("sess-004", List.of(UserMessage.from("first")),
                "text", "第一个问题", null, 0, "model", "{}");

        manager.saveInteraction("sess-004", List.of(UserMessage.from("second")),
                "confirm", "第二个问题", Arrays.asList("A", "B"), 1, "model", "{}");

        PendingInteraction pending = manager.loadInteraction("sess-004");
        assertEquals("第二个问题", pending.getQuestion());
        assertEquals("confirm", pending.getAskUserType());
        assertEquals(1, pending.getRetryCount());
    }

    @Test
    void cleanupExpired_移除超过超时时间的pending() {
        manager.saveInteraction("sess-old", List.of(UserMessage.from("old")),
                "text", "旧问题", null, 0, "model", "{}");
        manager.saveInteraction("sess-new", List.of(UserMessage.from("new")),
                "text", "新问题", null, 0, "model", "{}");

        // 清理超过 0 毫秒的 pending（即立即过期），但 "sess-new" 应被保留（刚保存）
        // 由于两个都是刚保存的，cleanupExpired(0) 不会清理它们
        manager.cleanupExpired(0);

        // 手动模拟旧 pending 的超时：用负数 timeout 强制清理所有
        manager.cleanupExpired(-1);

        assertFalse(manager.hasPending("sess-old"));
        assertFalse(manager.hasPending("sess-new"));
    }

    @Test
    void loadInteraction_不存在时返回null() {
        PendingInteraction pending = manager.loadInteraction("non-existent");
        assertNull(pending);
    }

    @Test
    void hasPending_不存在时返回false() {
        assertFalse(manager.hasPending("non-existent"));
    }

    @Test
    void clearInteraction_不存在时不报错() {
        assertDoesNotThrow(() -> manager.clearInteraction("non-existent"));
    }
}
