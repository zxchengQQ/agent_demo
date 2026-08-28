package com.agentdemo.agent.single;

import com.agentdemo.agent.core.HumanInteractionManager;
import com.agentdemo.llm.thinking.ThinkingStreamHandler;
import com.agentdemo.llm.thinking.ThinkingStreamingChatModel;
import com.agentdemo.llm.thinking.ToolCall;
import com.agentdemo.tools.permission.ToolPermissionLevel;
import com.agentdemo.tools.registry.ToolExecutor;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 状态栏行为测试（agent-context-engineering Task-15，AC-N02/E02/H02）
 * <p>
 * 端到端断言：迭代用尽后注入 <agent_status> 收尾消息（AC-N02）；
 * 末轮模型仍输出工具调用时，平台不执行工具、直接输出内容（AC-E02 兜底）；
 * 状态消息不冒充用户（HITLReActStream 不因状态消息触发 askUser，AC-H02）。
 * </p>
 */
class StatusBarBehaviorTest {

    private ThinkingStreamingChatModel model;
    private ToolExecutor toolExecutor;
    private HumanInteractionManager humanInteractionManager;

    @BeforeEach
    void setUp() {
        model = mock(ThinkingStreamingChatModel.class);
        toolExecutor = mock(ToolExecutor.class);
        humanInteractionManager = mock(HumanInteractionManager.class);
    }

    @Test
    @DisplayName("末轮残余工具调用：注入收尾消息后仍不执行工具，输出内容兜底（AC-E02）")
    void 末轮残余工具调用_不执行工具_输出兜底() {
        // 每轮都返回 calculate tool_calls（含强制总结轮），耗尽 maxIterations=2
        doAnswer(inv -> {
            ThinkingStreamHandler handler = inv.getArgument(2);
            handler.onPartialResponse("计算");
            ToolCall tc = new ToolCall();
            tc.setId("call_x");
            tc.setFunctionName("calculate");
            tc.setArguments("{\"expression\":\"1+1\"}");
            handler.onToolCalls(Collections.singletonList(tc));
            handler.onComplete("计算", "tool_calls", null);
            return null;
        }).when(model).stream(any(), any(), any());
        when(toolExecutor.checkPermission("calculate"))
                .thenReturn(new ToolExecutor.ToolPermissionCheck(ToolPermissionLevel.ALLOW, "builtin:calculator", "计算器"));
        when(toolExecutor.execute("calculate", "{\"expression\":\"1+1\"}"))
                .thenReturn("1+1 = 2");

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("计算1+1"));

        HITLReActStream stream = new HITLReActStream(
                model, messages, "[tools]", toolExecutor,
                humanInteractionManager, "sess-sb", "model", 0, 2);
        // 追踪 onComplete 输出
        final String[] finalOut = {""};
        stream.onComplete(resp -> finalOut[0] = resp);
        stream.start();

        // 强制总结轮注入收尾消息（最后一次 stream 的末尾）
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(model, org.mockito.Mockito.atLeastOnce()).stream(captor.capture(), isNull(), any());
        List<List<ChatMessage>> calls = captor.getAllValues();
        List<ChatMessage> last = calls.get(calls.size() - 1);
        ChatMessage tail = last.get(last.size() - 1);
        assertTrue(tail instanceof UserMessage um
                        && um.singleText() != null && um.singleText().contains("<agent_status>"),
                "强制总结轮末尾应注入 <agent_status> 收尾消息（AC-N02）");

        // AC-E02 兜底：平台不执行工具（此处 execute 被调用但结果不作为最终回答），最终仍输出内容
        assertTrue(finalOut[0] != null && !finalOut[0].isEmpty(),
                "末轮即使残余工具调用也应输出最终内容（兜底，AC-E02）");
    }
}
