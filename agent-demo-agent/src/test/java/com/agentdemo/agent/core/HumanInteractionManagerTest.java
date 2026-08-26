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

    // ==================== unified-chat-mode Task-02 新增：拆解上下文扩展 ====================

    /**
     * 验证标准：新字段默认值正确（mode=direct、subTasks/subtaskResults 空集合、currentTaskIndex=0）
     */
    @Test
    void pendingInteraction_新增字段默认值正确() {
        PendingInteraction pending = new PendingInteraction();

        assertEquals(PendingInteraction.MODE_DIRECT, pending.getMode(), "mode 默认应为 direct");
        assertTrue(pending.getSubTasks().isEmpty(), "subTasks 默认应为空集合");
        assertEquals(0, pending.getCurrentTaskIndex(), "currentTaskIndex 默认应为 0");
        assertTrue(pending.getSubtaskResults().isEmpty(), "subtaskResults 默认应为空集合");
    }

    /**
     * 验证标准：saveInteraction 带 mode 重载可保存 breakdown 模式
     * 关联 AC：AC-T05（子任务暂停时以 breakdown 模式保存）
     */
    @Test
    void saveInteraction_带mode重载_保存breakdown模式() {
        manager.saveInteraction("sess-mode", List.of(UserMessage.from("复杂任务")),
                "text", "请提供订单号", null, 0, "model", "{}", PendingInteraction.MODE_BREAKDOWN);

        assertEquals(PendingInteraction.MODE_BREAKDOWN, manager.loadInteraction("sess-mode").getMode(),
                "带 mode 重载应保存 breakdown 模式");
    }

    /**
     * 验证标准：旧签名保留兼容直答路径，mode 默认 direct
     */
    @Test
    void saveInteraction_旧签名_mode默认direct() {
        manager.saveInteraction("sess-old-sig", List.of(UserMessage.from("直答")),
                "text", "问题", null, 0, "model", "{}");

        assertEquals(PendingInteraction.MODE_DIRECT, manager.loadInteraction("sess-old-sig").getMode(),
                "旧签名调用 mode 应默认 direct");
    }

    /**
     * 验证标准：attachBreakdownContext 正确更新已存在 pending 的四个拆解上下文字段
     * 关联 AC：AC-M02（拆解-追问-恢复链路的上下文保存）
     */
    @Test
    void attachBreakdownContext_更新已存在pending的拆解上下文() {
        manager.saveInteraction("sess-bd", List.of(UserMessage.from("子任务2缺订单号")),
                "text", "请提供订单号", null, 0, "model", "{}", PendingInteraction.MODE_BREAKDOWN);
        List<SubTask> tasks = List.of(new SubTask(1, "调研竞品"), new SubTask(2, "查询订单"));

        manager.attachBreakdownContext("sess-bd", tasks, 1, List.of("调研结果A"));

        PendingInteraction pending = manager.loadInteraction("sess-bd");
        assertEquals(tasks, pending.getSubTasks(), "subTasks 应被更新为拆解计划");
        assertEquals(1, pending.getCurrentTaskIndex(), "currentTaskIndex 应为暂停时的子任务序号");
        assertEquals(List.of("调研结果A"), pending.getSubtaskResults(), "subtaskResults 应为已完成子任务结果");
        assertEquals(PendingInteraction.MODE_BREAKDOWN, pending.getMode(), "mode 应保持 breakdown");
    }

    /**
     * 验证标准：attachBreakdownContext 对不存在的 pending 不创建新条目（不抛异常）
     */
    @Test
    void attachBreakdownContext_pending不存在时不创建且不抛异常() {
        assertDoesNotThrow(() -> manager.attachBreakdownContext("non-existent",
                List.of(new SubTask(1, "任务")), 0, List.of()));

        assertFalse(manager.hasPending("non-existent"), "pending 不存在时不应被 attach 创建");
    }

    /**
     * 验证标准：超时清理回归--含拆解上下文的 pending 随对象整体清理
     * 关联 AC：AC-E03（会话超时清理等待状态）
     */
    @Test
    void cleanupExpired_含拆解上下文的pending整体清理() {
        manager.saveInteraction("sess-bd2", List.of(UserMessage.from("暂停")),
                "text", "问题", null, 0, "model", "{}", PendingInteraction.MODE_BREAKDOWN);
        manager.attachBreakdownContext("sess-bd2", List.of(new SubTask(1, "任务")), 0, List.of("结果"));
        assertTrue(manager.hasPending("sess-bd2"));

        manager.cleanupExpired(-1);

        assertFalse(manager.hasPending("sess-bd2"), "超时后含拆解上下文的 pending 应整体清理");
    }
}
