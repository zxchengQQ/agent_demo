package com.agentdemo.agent.single;

import com.agentdemo.agent.core.HitlTokenStream;
import com.agentdemo.agent.core.HumanInteractionManager;
import com.agentdemo.llm.thinking.ThinkingStreamHandler;
import com.agentdemo.llm.thinking.ThinkingStreamingChatModel;
import com.agentdemo.llm.thinking.ToolCall;
import com.agentdemo.tools.registry.ToolExecutor;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HITLReActStream loadSkill 拦截测试（技术方案 Task-13，AC-N01/T02/E01）
 * <p>
 * 业务含义：loadSkill 工具调用被拦截（askUser 先例）→ 委托 SkillToolInterceptor 激活 →
 * 触发 onSkillActivated 回调 → 热刷新 toolsJson（下一迭代可用）→ 回填观察值 → 不暂停循环。
 * </p>
 */
class HITLReActStreamSkillInterceptTest {

    private ThinkingStreamingChatModel model;
    private ToolExecutor toolExecutor;
    private HumanInteractionManager humanInteractionManager;
    private SkillToolInterceptor interceptor;

    @BeforeEach
    void setUp() {
        model = mock(ThinkingStreamingChatModel.class);
        toolExecutor = mock(ToolExecutor.class);
        humanInteractionManager = mock(HumanInteractionManager.class);
        interceptor = mock(SkillToolInterceptor.class);
    }

    private ToolCall toolCall(String id, String name, String args) {
        ToolCall tc = new ToolCall();
        tc.setId(id);
        tc.setFunctionName(name);
        tc.setArguments(args);
        return tc;
    }

    @Test
    void loadSkill拦截应触发激活回调并回填观察值且不暂停() {
        // 第一轮：loadSkill；第二轮：stop
        AtomicInteger round = new AtomicInteger(0);
        doAnswer(inv -> {
            ThinkingStreamHandler handler = inv.getArgument(2);
            int r = round.getAndIncrement();
            if (r == 0) {
                handler.onPartialResponse("加载技能");
                handler.onToolCalls(List.of(toolCall("call_ls", "loadSkill", "{\"skillName\":\"s1\"}")));
                handler.onComplete("加载技能", "tool_calls", null);
            } else {
                handler.onPartialResponse("已按技能回答");
                handler.onComplete("已按技能回答", "stop", null);
            }
            return null;
        }).when(model).stream(any(), anyString(), any());

        // 拦截器：激活成功 + 刷新 toolsJson
        when(interceptor.interceptLoadSkill("sess", "s1", "[tools]", 1))
                .thenReturn(new SkillToolInterceptor.SkillInterceptionResult(
                        "技能「技能一」已加载。指令：指令一", true,
                        "s1", "技能一", "AUTO", List.of("builtin:httpGet"), "[refreshed-tools]"));

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("系统提示"));
        messages.add(UserMessage.from("帮我写周报"));

        HITLReActStream stream = new HITLReActStream(
                model, messages, "[tools]", toolExecutor,
                humanInteractionManager, "sess", "model", 0, 8, interceptor);

        HitlTokenStream.SkillActivatedConsumer skillConsumer = mock(HitlTokenStream.SkillActivatedConsumer.class);
        HitlTokenStream.CompleteConsumer completeConsumer = mock(HitlTokenStream.CompleteConsumer.class);
        stream.onSkillActivated(skillConsumer).onComplete(completeConsumer);

        stream.start();

        // 激活回调触发，载荷四要素
        verify(skillConsumer).accept("s1", "技能一", "AUTO", List.of("builtin:httpGet"));
        // 完整回复
        verify(completeConsumer).accept("已按技能回答");

        // 第二轮调用使用了热刷新后的 toolsJson（toolsJson 可变生效）
        ArgumentCaptor<String> toolsJsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(model, org.mockito.Mockito.times(2)).stream(any(), toolsJsonCaptor.capture(), any());
        assertThat(toolsJsonCaptor.getAllValues().get(0)).isEqualTo("[tools]");
        assertThat(toolsJsonCaptor.getAllValues().get(1)).isEqualTo("[refreshed-tools]");

        // 观察值已回填为 ToolExecutionResultMessage（不暂停，循环继续到 stop）
        assertThat(messages).anyMatch(m -> m instanceof ToolExecutionResultMessage
                && ((ToolExecutionResultMessage) m).text().contains("技能一"));
    }

    @Test
    void loadSkill激活失败应回填引导观察值且不触发激活回调() {
        AtomicInteger round = new AtomicInteger(0);
        doAnswer(inv -> {
            ThinkingStreamHandler handler = inv.getArgument(2);
            if (round.getAndIncrement() == 0) {
                handler.onPartialResponse("加载技能");
                handler.onToolCalls(List.of(toolCall("call_ls", "loadSkill", "{\"skillName\":\"ghost\"}")));
                handler.onComplete("加载技能", "tool_calls", null);
            } else {
                handler.onPartialResponse("技能不存在，我换个方式");
                handler.onComplete("技能不存在，我换个方式", "stop", null);
            }
            return null;
        }).when(model).stream(any(), anyString(), any());

        when(interceptor.interceptLoadSkill("sess", "ghost", "[tools]", 1))
                .thenReturn(SkillToolInterceptor.SkillInterceptionResult.noIntercept(
                        "技能不存在: ghost。可用技能: s1, s2。"));

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("系统提示"));
        messages.add(UserMessage.from("用技能处理"));

        HITLReActStream stream = new HITLReActStream(
                model, messages, "[tools]", toolExecutor,
                humanInteractionManager, "sess", "model", 0, 8, interceptor);

        HitlTokenStream.SkillActivatedConsumer skillConsumer = mock(HitlTokenStream.SkillActivatedConsumer.class);
        stream.onSkillActivated(skillConsumer);
        stream.start();

        verify(skillConsumer, org.mockito.Mockito.never()).accept(any(), any(), any(), any());
        assertThat(messages).anyMatch(m -> m instanceof ToolExecutionResultMessage
                && ((ToolExecutionResultMessage) m).text().contains("不存在"));
    }

    @Test
    void 拦截器为null时loadSkill按普通工具执行() {
        // 无拦截器（app 模块等场景）：loadSkill 走 ToolExecutor 执行；第二轮 stop 结束循环
        AtomicInteger round = new AtomicInteger(0);
        doAnswer(inv -> {
            ThinkingStreamHandler handler = inv.getArgument(2);
            if (round.getAndIncrement() == 0) {
                handler.onPartialResponse("调用工具");
                handler.onToolCalls(List.of(toolCall("call_ls", "loadSkill", "{\"skillName\":\"s1\"}")));
                handler.onComplete("调用工具", "tool_calls", null);
            } else {
                handler.onPartialResponse("完成");
                handler.onComplete("完成", "stop", null);
            }
            return null;
        }).when(model).stream(any(), anyString(), any());

        // loadSkill 权限豁免恒 ALLOW（与真实注册一致），普通执行路径不被权限拦截
        when(toolExecutor.checkPermission("loadSkill"))
                .thenReturn(new ToolExecutor.ToolPermissionCheck(
                        com.agentdemo.tools.permission.ToolPermissionLevel.ALLOW, "builtin:loadSkill", "加载技能"));
        when(toolExecutor.execute("loadSkill", "{\"skillName\":\"s1\"}")).thenReturn("技能已加载");

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("系统提示"));
        messages.add(UserMessage.from("触发"));

        HITLReActStream stream = new HITLReActStream(
                model, messages, "[tools]", toolExecutor,
                humanInteractionManager, "sess", "model", 0, 8);

        stream.start();

        verify(toolExecutor).execute("loadSkill", "{\"skillName\":\"s1\"}");
    }
}
