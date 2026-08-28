package com.agentdemo.agent.single;

import com.agentdemo.agent.core.HitlTokenStream;
import com.agentdemo.agent.core.HumanInteractionManager;
import com.agentdemo.llm.thinking.ThinkingStreamHandler;
import com.agentdemo.llm.thinking.ThinkingStreamingChatModel;
import com.agentdemo.llm.thinking.ToolCall;
import com.agentdemo.tools.permission.ToolPermissionLevel;
import com.agentdemo.tools.registry.ToolExecutor;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * HITLReActStream 测试
 * <p>
 * 验证标准来源：Task-02 验证标准
 * 关联 AC：AC-N01（歧义追问）、AC-N02（关键操作确认）、AC-N03（恢复执行）、
 *         AC-S01（追问上限）、AC-M01（上下文保持）
 * </p>
 */
class HITLReActStreamTest {

    private ThinkingStreamingChatModel model;
    private ToolExecutor toolExecutor;
    private HumanInteractionManager humanInteractionManager;

    @BeforeEach
    void setUp() {
        model = mock(ThinkingStreamingChatModel.class);
        toolExecutor = mock(ToolExecutor.class);
        humanInteractionManager = mock(HumanInteractionManager.class);
    }

    /**
     * 模拟单轮 LLM 调用返回 stop（无工具调用）
     */
    private Object mockSingleRoundStop(org.mockito.invocation.InvocationOnMock invocation) {
        ThinkingStreamHandler handler = invocation.getArgument(2);
        handler.onPartialResponse("最终回答");
        handler.onComplete("最终回答", "stop", null);
        return null;
    }

    /**
     * 模拟单轮 LLM 调用返回 tool_calls（指定工具名和参数）
     */
    private Object mockSingleRoundToolCalls(org.mockito.invocation.InvocationOnMock invocation,
                                            String toolName, String args) {
        ThinkingStreamHandler handler = invocation.getArgument(2);
        handler.onPartialResponse("需要调用工具");

        ToolCall tc = new ToolCall();
        tc.setId("call_001");
        tc.setFunctionName(toolName);
        tc.setArguments(args);

        handler.onToolCalls(Collections.singletonList(tc));
        handler.onComplete("需要调用工具", "tool_calls", null);
        return null;
    }

    /**
     * 模拟单轮 LLM 调用返回多个 tool_calls（支持 allow+ask 混合场景）
     *
     * @param toolCalls 元素为 [工具名, 参数JSON]，toolCall id 依次为 call_m0/call_m1/...
     */
    private Object mockSingleRoundMultiToolCalls(org.mockito.invocation.InvocationOnMock invocation,
                                                 List<String[]> toolCalls) {
        ThinkingStreamHandler handler = invocation.getArgument(2);
        handler.onPartialResponse("需要调用工具");

        List<ToolCall> tcs = new ArrayList<>();
        for (int i = 0; i < toolCalls.size(); i++) {
            ToolCall tc = new ToolCall();
            tc.setId("call_m" + i);
            tc.setFunctionName(toolCalls.get(i)[0]);
            tc.setArguments(toolCalls.get(i)[1]);
            tcs.add(tc);
        }

        handler.onToolCalls(tcs);
        handler.onComplete("需要调用工具", "tool_calls", null);
        return null;
    }

    // ========== 验证标准：askUser 工具拦截 ==========

    @Test
    void askUser调用应拦截并触发onAskUser回调() {
        String askUserArgs = "{\"type\":\"text\",\"question\":\"请提供订单号\",\"options\":[]}";
        doAnswer(inv -> mockSingleRoundToolCalls(inv, "askUser", askUserArgs))
                .when(model).stream(any(), any(), any());

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("系统提示"));
        messages.add(UserMessage.from("帮我查订单"));

        HITLReActStream stream = new HITLReActStream(
                model, messages, "[tools]", toolExecutor,
                humanInteractionManager, "sess-001", "model-001", 0, 8);

        HitlTokenStream.AskUserConsumer askUserConsumer = mock(HitlTokenStream.AskUserConsumer.class);
        HitlTokenStream.CompleteConsumer completeConsumer = mock(HitlTokenStream.CompleteConsumer.class);

        stream.onAskUser(askUserConsumer);
        stream.onComplete(completeConsumer);
        stream.start();

        // 验证 onAskUser 被触发，携带正确参数
        verify(askUserConsumer).accept("text", "请提供订单号", Collections.emptyList(), 0);
        // 验证 onComplete 不被调用（暂停状态）
        verify(completeConsumer, never()).accept(anyString());
    }

    @Test
    void askUser拦截后应保存状态到HumanInteractionManager() {
        String askUserArgs = "{\"type\":\"confirm\",\"question\":\"确认删除？\",\"options\":[\"确认\",\"取消\"]}";
        doAnswer(inv -> mockSingleRoundToolCalls(inv, "askUser", askUserArgs))
                .when(model).stream(any(), any(), any());

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("删除文件"));

        HITLReActStream stream = new HITLReActStream(
                model, messages, "[tools]", toolExecutor,
                humanInteractionManager, "sess-002", "model-002", 0, 8);

        stream.onAskUser(mock(HitlTokenStream.AskUserConsumer.class));
        stream.start();

        // 验证状态已保存
        verify(humanInteractionManager).saveInteraction(
                eq("sess-002"), any(), eq("confirm"), eq("确认删除？"),
                eq(List.of("确认", "取消")), eq(0), eq("model-002"), eq("[tools]"));
    }

    @Test
    void askUser拦截后onAction应被触发() {
        String askUserArgs = "{\"type\":\"text\",\"question\":\"问题\",\"options\":[]}";
        doAnswer(inv -> mockSingleRoundToolCalls(inv, "askUser", askUserArgs))
                .when(model).stream(any(), any(), any());

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("test"));

        HITLReActStream stream = new HITLReActStream(
                model, messages, "[tools]", toolExecutor,
                humanInteractionManager, "sess-003", "model-001", 0, 8);

        HitlTokenStream.ActionConsumer actionConsumer = mock(HitlTokenStream.ActionConsumer.class);
        stream.onAction(actionConsumer);
        stream.onAskUser(mock(HitlTokenStream.AskUserConsumer.class));
        stream.start();

        // 验证 onAction 被触发（前端显示 Agent 正在调用工具）
        verify(actionConsumer).accept("askUser", askUserArgs, 1);
    }

    // ========== 验证标准：非 askUser 工具正常执行 ==========

    @Test
    void 非askUser工具应正常执行() {
        int[] callCount = {0};
        doAnswer(inv -> {
            callCount[0]++;
            if (callCount[0] == 1) {
                mockSingleRoundToolCalls(inv, "calculate", "{\"expression\":\"1+1\"}");
            } else {
                mockSingleRoundStop(inv);
            }
            return null;
        }).when(model).stream(any(), any(), any());

        when(toolExecutor.checkPermission("calculate"))
                .thenReturn(new ToolExecutor.ToolPermissionCheck(
                        ToolPermissionLevel.ALLOW, "builtin:calculator", "计算器"));
        when(toolExecutor.execute("calculate", "{\"expression\":\"1+1\"}"))
                .thenReturn("1+1 = 2");

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("计算1+1"));

        HITLReActStream stream = new HITLReActStream(
                model, messages, "[tools]", toolExecutor,
                humanInteractionManager, "sess-004", "model-001", 0, 8);

        HitlTokenStream.ActionConsumer actionConsumer = mock(HitlTokenStream.ActionConsumer.class);
        HitlTokenStream.ObservationConsumer observationConsumer = mock(HitlTokenStream.ObservationConsumer.class);
        HitlTokenStream.CompleteConsumer completeConsumer = mock(HitlTokenStream.CompleteConsumer.class);

        stream.onAction(actionConsumer);
        stream.onObservation(observationConsumer);
        stream.onComplete(completeConsumer);
        stream.start();

        verify(actionConsumer).accept("calculate", "{\"expression\":\"1+1\"}", 1);
        verify(observationConsumer).accept("1+1 = 2", 1);
        verify(completeConsumer).accept("最终回答");
        // 验证未保存 HITL 状态
        verify(humanInteractionManager, never()).saveInteraction(
                anyString(), any(), anyString(), anyString(), any(), anyInt(), anyString(), anyString());
    }

    // ========== 验证标准：追问次数上限 ==========

    @Test
    void retryCount超过上限应返回错误Observation() {
        String askUserArgs = "{\"type\":\"text\",\"question\":\"再次追问\",\"options\":[]}";
        doAnswer(inv -> mockSingleRoundToolCalls(inv, "askUser", askUserArgs))
                .when(model).stream(any(), any(), any());

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("test"));

        // retryCount = 3，已达上限
        HITLReActStream stream = new HITLReActStream(
                model, messages, "[tools]", toolExecutor,
                humanInteractionManager, "sess-005", "model-001", 3, 8);

        HitlTokenStream.ObservationConsumer observationConsumer = mock(HitlTokenStream.ObservationConsumer.class);
        HitlTokenStream.AskUserConsumer askUserConsumer = mock(HitlTokenStream.AskUserConsumer.class);

        stream.onObservation(observationConsumer);
        stream.onAskUser(askUserConsumer);
        stream.start();

        // 验证返回了错误 Observation
        verify(observationConsumer).accept(contains("已达最大追问次数"), eq(1));
        // 验证 onAskUser 未被触发
        verify(askUserConsumer, never()).accept(anyString(), anyString(), any(), anyInt());
        // 验证未保存状态
        verify(humanInteractionManager, never()).saveInteraction(
                anyString(), any(), anyString(), anyString(), any(), anyInt(), anyString(), anyString());
    }

    // ========== 验证标准：无工具调用时正常完成 ==========

    @Test
    void 无工具调用应正常完成() {
        doAnswer(this::mockSingleRoundStop).when(model).stream(any(), any(), any());

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("你好"));

        HITLReActStream stream = new HITLReActStream(
                model, messages, "[tools]", toolExecutor,
                humanInteractionManager, "sess-006", "model-001", 0, 8);

        HitlTokenStream.FinalAnswerConsumer finalAnswerConsumer = mock(HitlTokenStream.FinalAnswerConsumer.class);
        HitlTokenStream.CompleteConsumer completeConsumer = mock(HitlTokenStream.CompleteConsumer.class);

        stream.onFinalAnswer(finalAnswerConsumer);
        stream.onComplete(completeConsumer);
        stream.start();

        verify(finalAnswerConsumer).accept(1);
        verify(completeConsumer).accept("最终回答");
    }

    // ========== 验证标准：LLM 调用异常 ==========

    @Test
    void LLM异常应触发onError() {
        RuntimeException exception = new RuntimeException("LLM 连接失败");
        doAnswer(inv -> {
            ThinkingStreamHandler handler = inv.getArgument(2);
            handler.onError(exception);
            return null;
        }).when(model).stream(any(), any(), any());

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("触发错误"));

        HITLReActStream stream = new HITLReActStream(
                model, messages, "[tools]", toolExecutor,
                humanInteractionManager, "sess-007", "model-001", 0, 8);

        HitlTokenStream.ErrorConsumer errorConsumer = mock(HitlTokenStream.ErrorConsumer.class);
        stream.onError(errorConsumer);
        stream.start();

        verify(errorConsumer).accept(exception);
    }

    // ========== 验证标准：ask 级工具权限确认拦截（Task-10） ==========

    @Test
    void ask级工具应拦截暂停并触发onToolConfirm() {
        String httpArgs = "{\"url\":\"https://example.com\"}";
        doAnswer(inv -> mockSingleRoundToolCalls(inv, "httpGet", httpArgs))
                .when(model).stream(any(), any(), any());

        // checkPermission 裁决 ASK
        when(toolExecutor.checkPermission("httpGet"))
                .thenReturn(new ToolExecutor.ToolPermissionCheck(
                        ToolPermissionLevel.ASK, "builtin:httpGet", "发起 HTTP GET 请求"));

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("访问一个网页"));

        HITLReActStream stream = new HITLReActStream(
                model, messages, "[tools]", toolExecutor,
                humanInteractionManager, "sess-tc-001", "model-001", 0, 8);

        HitlTokenStream.ToolConfirmConsumer toolConfirmConsumer = mock(HitlTokenStream.ToolConfirmConsumer.class);
        HitlTokenStream.CompleteConsumer completeConsumer = mock(HitlTokenStream.CompleteConsumer.class);

        stream.onToolConfirm(toolConfirmConsumer);
        stream.onComplete(completeConsumer);
        stream.start();

        // 验证 onToolConfirm 被触发，携带 toolCallId/工具名/描述/参数 JSON
        verify(toolConfirmConsumer).accept("call_001", "httpGet", "发起 HTTP GET 请求", httpArgs);
        // 验证 onComplete 不被调用（暂停状态）
        verify(completeConsumer, never()).accept(anyString());
        // 业务含义（Task-10 快照外移）：HITLReActStream 不再负责快照持久化，
        // handleToolConfirm 仅触发回调后暂停，由宿主（UnifiedChatStream）补保存
        verify(humanInteractionManager, never()).saveToolConfirmInteraction(
                anyString(), any(), anyString(), anyString(), anyString(), anyString(), anyString());
        // 验证暂停时 messages 不含该工具的 ToolExecutionResultMessage（待恢复时回填）
        assertTrue(messages.stream().noneMatch(m -> m instanceof ToolExecutionResultMessage
                        && "httpGet".equals(((ToolExecutionResultMessage) m).toolName())),
                "暂停时不应含 ask 工具的 ToolExecutionResultMessage");
        // 验证 ask 工具方法体未执行
        verify(toolExecutor, never()).execute("httpGet", httpArgs);
    }

    @Test
    void allow工具在ask之前的混合场景allow已执行ask拦截暂停() {
        // 第一轮同轮调用 calculate(allow) + httpGet(ask)
        doAnswer(inv -> mockSingleRoundMultiToolCalls(inv, List.of(
                new String[]{"calculate", "{\"expression\":\"1+1\"}"},
                new String[]{"httpGet", "{\"url\":\"https://example.com\"}"})))
                .when(model).stream(any(), any(), any());

        when(toolExecutor.checkPermission("calculate"))
                .thenReturn(new ToolExecutor.ToolPermissionCheck(
                        ToolPermissionLevel.ALLOW, "builtin:calculator", "计算器"));
        when(toolExecutor.checkPermission("httpGet"))
                .thenReturn(new ToolExecutor.ToolPermissionCheck(
                        ToolPermissionLevel.ASK, "builtin:httpGet", "发起 HTTP GET 请求"));
        when(toolExecutor.execute("calculate", "{\"expression\":\"1+1\"}"))
                .thenReturn("1+1 = 2");

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("计算并访问网页"));

        HITLReActStream stream = new HITLReActStream(
                model, messages, "[tools]", toolExecutor,
                humanInteractionManager, "sess-tc-002", "model-001", 0, 8);

        HitlTokenStream.ObservationConsumer observationConsumer = mock(HitlTokenStream.ObservationConsumer.class);
        HitlTokenStream.ToolConfirmConsumer toolConfirmConsumer = mock(HitlTokenStream.ToolConfirmConsumer.class);

        stream.onObservation(observationConsumer);
        stream.onToolConfirm(toolConfirmConsumer);
        stream.start();

        // allow 工具已执行且回填 Observation
        verify(toolExecutor).execute("calculate", "{\"expression\":\"1+1\"}");
        verify(observationConsumer).accept("1+1 = 2", 1);
        // ask 工具被拦截，方法体未执行
        verify(toolExecutor, never()).execute("httpGet", "{\"url\":\"https://example.com\"}");
        // onToolConfirm 触发的是第二个 toolCall（httpGet），携带 toolCallId=call_m1
        verify(toolConfirmConsumer).accept("call_m1", "httpGet", "发起 HTTP GET 请求", "{\"url\":\"https://example.com\"}");
        // 业务含义（Task-10 快照外移）：HITLReActStream 不保存快照，宿主负责
        verify(humanInteractionManager, never()).saveToolConfirmInteraction(
                anyString(), any(), anyString(), anyString(), anyString(), anyString(), anyString());
        // 已执行的 allow 结果已在 messages 中（游标完整性）
        assertTrue(messages.stream().anyMatch(m -> m instanceof ToolExecutionResultMessage
                        && "calculate".equals(((ToolExecutionResultMessage) m).toolName())),
                "allow 工具结果应已回填到 messages");
    }

    @Test
    void deny级工具应回填拒绝Observation并继续不暂停() {
        int[] callCount = {0};
        doAnswer(inv -> {
            callCount[0]++;
            if (callCount[0] == 1) {
                mockSingleRoundToolCalls(inv, "deniedAction", "{}");
            } else {
                mockSingleRoundStop(inv);
            }
            return null;
        }).when(model).stream(any(), any(), any());

        when(toolExecutor.checkPermission("deniedAction"))
                .thenReturn(new ToolExecutor.ToolPermissionCheck(
                        ToolPermissionLevel.DENY, "builtin:deniedAction", "被禁止的动作"));
        // execute 内置 deny 兜底返回拒绝文案（方法体零触发）
        when(toolExecutor.execute("deniedAction", "{}"))
                .thenReturn("该工具已被禁止调用，无法执行。请告知用户无法完成此操作，或调整方案。");

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("诱导调用被禁止的工具"));

        HITLReActStream stream = new HITLReActStream(
                model, messages, "[tools]", toolExecutor,
                humanInteractionManager, "sess-tc-003", "model-001", 0, 8);

        HitlTokenStream.ObservationConsumer observationConsumer = mock(HitlTokenStream.ObservationConsumer.class);
        HitlTokenStream.CompleteConsumer completeConsumer = mock(HitlTokenStream.CompleteConsumer.class);
        HitlTokenStream.ToolConfirmConsumer toolConfirmConsumer = mock(HitlTokenStream.ToolConfirmConsumer.class);

        stream.onObservation(observationConsumer);
        stream.onComplete(completeConsumer);
        stream.onToolConfirm(toolConfirmConsumer);
        stream.start();

        // deny 工具回填拒绝 Observation，不暂停
        verify(observationConsumer).accept(contains("禁止"), eq(1));
        // 循环继续并正常完成（第二轮 stop）
        verify(completeConsumer).accept("最终回答");
        // 未触发权限确认暂停
        verify(toolConfirmConsumer, never()).accept(anyString(), anyString(), any(), anyString());
        verify(humanInteractionManager, never()).saveToolConfirmInteraction(
                anyString(), any(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    // ========== 验证标准：追问次数上限（AC-S02）——retryCount>=3 返回错误 Observation，不暂停 ==========

    @Test
    void retryCount达上限_应返回错误Observation而非暂停() {
        // 业务含义：同一会话连续追问达到 MAX_RETRY_COUNT(3) 后，再调用 askUser 不再进入等待
        // （避免无限循环追问），而是将"已达上限"作为错误 Observation 回填 ReAct 上下文，
        // 让 Agent 自行终止任务并告知用户（AC-S02）。不保存暂停状态、不触发 onAskUser。
        String askUserArgs = "{\"type\":\"text\",\"question\":\"重复提问\",\"options\":[]}";
        doAnswer(inv -> mockSingleRoundToolCalls(inv, "askUser", askUserArgs))
                .when(model).stream(any(), any(), any());

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("系统提示"));
        messages.add(UserMessage.from("输入"));

        // retryCount=3 >= MAX_RETRY_COUNT(3)：已达追问上限
        HITLReActStream stream = new HITLReActStream(
                model, messages, "[tools]", toolExecutor,
                humanInteractionManager, "sess-s02", "model-001", 3, 8);

        HitlTokenStream.ObservationConsumer observationConsumer = mock(HitlTokenStream.ObservationConsumer.class);
        HitlTokenStream.AskUserConsumer askUserConsumer = mock(HitlTokenStream.AskUserConsumer.class);

        stream.onObservation(observationConsumer);
        stream.onAskUser(askUserConsumer);
        stream.start();

        // 回填错误 Observation（携带追问上限提示），而非触发 onAskUser 暂停
        verify(observationConsumer, atLeastOnce()).accept(contains("已达最大追问次数"), anyInt());
        verify(askUserConsumer, never()).accept(anyString(), anyString(), any(), anyInt());
        // 达到上限不保存暂停状态（不进入 WAITING_USER 等待用户）
        verify(humanInteractionManager, never()).saveInteraction(
                anyString(), any(), anyString(), anyString(), any(), anyInt(), anyString(), anyString());
        // 错误 Observation 已追加为 ToolExecutionResultMessage（ReAct 上下文可见）
        assertTrue(messages.stream().anyMatch(m -> m instanceof ToolExecutionResultMessage),
                "应追加错误 Observation 的 ToolExecutionResultMessage，实际: " + messages);
    }

    // ==================== Task-08: 末轮收尾状态注入（agent-context-engineering，AC-N02/E02/H02） ====================

    /** 捕获最后一次 model.stream 的消息列表 */
    private List<ChatMessage> captureLastStreamMessages() {
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(model, atLeastOnce()).stream(captor.capture(), isNull(), any());
        List<List<ChatMessage>> allCalls = captor.getAllValues();
        return allCalls.get(allCalls.size() - 1);
    }

    @Test
    void 迭代用尽_强制总结前注入收尾状态消息() {
        // 每次 stream 都返回 calculator tool_calls，耗尽 maxIterations=2
        doAnswer(inv -> mockSingleRoundToolCalls(inv, "calculate", "{\"expression\":\"1+1\"}"))
                .when(model).stream(any(), any(), any());
        when(toolExecutor.checkPermission("calculate"))
                .thenReturn(new ToolExecutor.ToolPermissionCheck(
                        ToolPermissionLevel.ALLOW, "builtin:calculator", "计算器"));
        when(toolExecutor.execute("calculate", "{\"expression\":\"1+1\"}"))
                .thenReturn("1+1 = 2");

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("计算1+1"));

        HITLReActStream stream = new HITLReActStream(
                model, messages, "[tools]", toolExecutor,
                humanInteractionManager, "sess-status", "model-001", 0, 2);
        stream.start();

        List<ChatMessage> lastMessages = captureLastStreamMessages();
        ChatMessage last = lastMessages.get(lastMessages.size() - 1);
        assertTrue(last instanceof UserMessage, "末轮前应注入收尾状态消息");
        String status = ((UserMessage) last).singleText();
        assertTrue(status.contains("<agent_status>"), "收尾消息应以 <agent_status> 包裹，实际: " + status);
        assertTrue(status.contains("2/2"), "收尾消息应含迭代读数 2/2，实际: " + status);
        assertTrue(status.contains("不要再发起工具调用"),
                "收尾消息应含收尾操作策略");
    }

    @Test
    void 正常stop路径_不注入收尾状态消息() {
        doAnswer(inv -> mockSingleRoundStop(inv)).when(model).stream(any(), any(), any());

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("你好"));

        HITLReActStream stream = new HITLReActStream(
                model, messages, "[tools]", toolExecutor,
                humanInteractionManager, "sess-stop", "model-001", 0, 8);
        stream.start();

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(model, atLeastOnce()).stream(captor.capture(), any(), any());
        for (List<ChatMessage> call : captor.getAllValues()) {
            assertTrue(call.stream().noneMatch(m -> m instanceof UserMessage um
                            && um.singleText() != null && um.singleText().contains("<agent_status>")),
                    "正常 stop 路径不应注入收尾状态消息");
        }
    }
}
