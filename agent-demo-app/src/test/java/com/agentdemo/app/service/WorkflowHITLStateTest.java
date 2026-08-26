package com.agentdemo.app.service;

import com.agentdemo.app.core.WorkflowTemplate;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * WorkflowHITLState 快照与 ResumableExecutionState 扩展测试（工作流 HITL Task-02）
 * <p>
 * 业务含义：验证 HITL 暂停状态的完整存储能力——模式/提问数据/暂停步骤/消息列表/追问计数，
 * 以及 ResumableExecutionState 新增 hitlState 字段的语义（失败暂停为 null，HITL 暂停携带快照，
 * AC-M01/AC-M02/AC-T01 前置）。
 * </p>
 */
class WorkflowHITLStateTest {

    @Test
    void state_shouldCarryAllFields() {
        WorkflowHITLState.PendingStep pendingStep =
                new WorkflowHITLState.PendingStep(1, "研究 Agent", "输入", 0);
        WorkflowHITLState.AskUserData askUserData =
                new WorkflowHITLState.AskUserData("confirm", "确认执行？", List.of("确认", "取消"), 0);

        WorkflowHITLState state = new WorkflowHITLState(
                WorkflowHITLState.MODE_CHECKPOINT, askUserData, pendingStep, List.of(), 0);

        assertEquals(WorkflowHITLState.MODE_CHECKPOINT, state.getHitlMode());
        assertSame(askUserData, state.getAskUserData());
        assertSame(pendingStep, state.getPendingStep());
        assertEquals(0, state.getRetryCount());
    }

    @Test
    void askUserState_shouldCarryMessagesForReActResume() {
        // 业务含义：askUser 模式保存完整 ReAct 消息列表（含系统提示词），恢复时续跑
        WorkflowHITLState.PendingStep pendingStep =
                new WorkflowHITLState.PendingStep(0, "研究 Agent", "输入", 0);
        List<ChatMessage> messages = List.of(
                new SystemMessage("你是研究助手"),
                new UserMessage("请分析 AI Agent"));

        WorkflowHITLState state = new WorkflowHITLState(
                WorkflowHITLState.MODE_ASK_USER,
                new WorkflowHITLState.AskUserData("text", "请提供主题", List.of(), 1),
                pendingStep, messages, 1);

        assertEquals(WorkflowHITLState.MODE_ASK_USER, state.getHitlMode());
        assertEquals(2, state.getMessages().size());
        assertEquals(1, state.getRetryCount());
    }

    @Test
    void toolConfirmState_shouldCarryToolConfirmData() {
        // 业务含义：toolConfirm 模式保存被拦截工具调用四要素（toolCallId/工具名/描述/参数），
        // 恢复时 executeHitlResume 按 toolCallId 回填结果消息（AC-M02/AC-H01 前置）。
        WorkflowHITLState.PendingStep pendingStep =
                new WorkflowHITLState.PendingStep(1, "研究 Agent", "输入", 0);
        WorkflowHITLState.ToolConfirmData toolConfirmData =
                new WorkflowHITLState.ToolConfirmData("call_http", "httpGet", "发起 HTTP GET 请求", "{\"url\":\"https://example.com\"}");

        WorkflowHITLState state = new WorkflowHITLState(
                WorkflowHITLState.MODE_TOOL_CONFIRM, toolConfirmData, pendingStep, List.of(), 0);

        // hitlMode 常量与前端 waitingHitlMode='toolConfirm' 契约一致（Task-17）
        assertEquals("toolConfirm", WorkflowHITLState.MODE_TOOL_CONFIRM, "MODE_TOOL_CONFIRM 常量值应为 toolConfirm");
        assertEquals(WorkflowHITLState.MODE_TOOL_CONFIRM, state.getHitlMode());
        assertSame(toolConfirmData, state.getToolConfirmData());
        assertSame(pendingStep, state.getPendingStep());
        assertEquals("call_http", state.getToolConfirmData().getToolCallId());
        assertEquals("httpGet", state.getToolConfirmData().getToolName());
        assertEquals("发起 HTTP GET 请求", state.getToolConfirmData().getToolDescription());
        assertEquals("{\"url\":\"https://example.com\"}", state.getToolConfirmData().getArguments());
        assertEquals(0, state.getRetryCount());
    }

    @Test
    void toolConfirmState_shouldKeepMessagesForReActResume() {
        // 业务含义：toolConfirm 模式同样保存 ReAct 消息列表（游标完整性），
        // 恢复时在暂停点追加 ToolExecutionResultMessage 后续跑（AC-M02）。
        List<ChatMessage> messages = List.of(
                new SystemMessage("你是研究助手"),
                new UserMessage("请访问网页"));
        WorkflowHITLState state = new WorkflowHITLState(
                WorkflowHITLState.MODE_TOOL_CONFIRM,
                new WorkflowHITLState.ToolConfirmData("call_http", "httpGet", "发起 HTTP GET 请求", "{\"url\":\"https://example.com\"}"),
                new WorkflowHITLState.PendingStep(1, "研究 Agent", "请访问网页", 0),
                messages, 0);

        assertEquals(2, state.getMessages().size(), "toolConfirm 模式应保存 ReAct 消息列表");
    }

    @Test
    void resumableState_threeArgConstructor_shouldHaveNullHitlState() {
        // 业务含义：失败暂停（P3 既有）走三参构造器，hitlState 为 null——与 PAUSED 流程零回归
        ResumableExecutionState state = new ResumableExecutionState(
                WorkflowTemplate.builder().id("tpl-1").build(), Map.of("topic", "x"), "model-1");
        assertNull(state.getHitlState(), "失败暂停快照不应携带 HITL 状态");
    }

    @Test
    void resumableState_fourArgConstructor_shouldCarryHitlState() {
        // 业务含义：HITL 暂停走四参构造器携带快照，恢复时据此续跑
        WorkflowHITLState.PendingStep pendingStep =
                new WorkflowHITLState.PendingStep(0, "研究 Agent", "输入", 0);
        WorkflowHITLState hitlState = new WorkflowHITLState(
                WorkflowHITLState.MODE_CHECKPOINT,
                new WorkflowHITLState.AskUserData("confirm", "确认？", List.of("确认", "取消"), 0),
                pendingStep, List.of(), 0);

        ResumableExecutionState state = new ResumableExecutionState(
                WorkflowTemplate.builder().id("tpl-1").build(), Map.of("topic", "x"), "model-1", hitlState);

        assertSame(hitlState, state.getHitlState(), "HITL 暂停快照应携带 hitlState");
        // 既有三字段语义保持（恢复重放策略需要）
        assertEquals("tpl-1", state.getTemplate().getId());
        assertEquals("model-1", state.getModelId());
    }
}
