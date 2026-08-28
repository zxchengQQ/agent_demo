package com.agentdemo.agent.skill;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.agent.core.HitlTokenStream;
import com.agentdemo.agent.core.HumanInteractionManager;
import com.agentdemo.agent.single.HITLReActStream;
import com.agentdemo.agent.single.SessionToolResolver;
import com.agentdemo.agent.single.SkillToolInterceptor;
import com.agentdemo.agent.single.SkillToolInterceptorImpl;
import com.agentdemo.llm.thinking.ThinkingStreamHandler;
import com.agentdemo.llm.thinking.ThinkingStreamingChatModel;
import com.agentdemo.llm.thinking.ToolCall;
import com.agentdemo.skill.config.SkillProperties;
import com.agentdemo.skill.entity.ScriptParam;
import com.agentdemo.skill.entity.SkillDefinition;
import com.agentdemo.skill.entity.SkillScript;
import com.agentdemo.skill.script.SkillScriptExecutor;
import com.agentdemo.skill.script.SkillScriptToolRegistrar;
import com.agentdemo.skill.session.SkillSessionManager;
import com.agentdemo.skill.store.SkillStore;
import com.agentdemo.skill.tool.SkillLoadTool;
import com.agentdemo.tools.permission.ToolPermissionLevel;
import com.agentdemo.tools.registry.ToolExecutor;
import com.agentdemo.tools.registry.ToolRegistry;
import com.agentdemo.tools.registry.ToolSchemaConverter;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
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
 * 激活链路与脚本工具集成测试（CR-001 Task-34，AC-N01/T01/T02/T03/T04/T05/S06/E01/M01）
 * <p>
 * 业务含义：mock LLM 返回 tool_calls 序列（loadSkill -> 自带脚本工具），验证：
 * 拦截激活 + 事件（载荷=脚本工具名）+ 热刷新（下一迭代可调用脚本工具）、脚本工具默认 ALLOW
 * 自主执行（不弹 tool_confirm，AC-T03）、脚本执行失败/护栏拦截降级不中断（AC-T04/S06）。
 * </p>
 */
class SkillActivationIntegrationTest {

    private ThinkingStreamingChatModel model;
    private ToolExecutor toolExecutor;
    private HumanInteractionManager humanInteractionManager;
    private SkillStore skillStore;
    private SkillSessionManager skillSessionManager;
    private SessionToolResolver sessionToolResolver;
    private SkillToolInterceptor interceptor;
    private ToolRegistry toolRegistry;

    private static final String SCRIPT_TOOL = "skill_data_query_assistant_http_get";

    @BeforeEach
    void setUp() {
        model = mock(ThinkingStreamingChatModel.class);
        toolExecutor = mock(ToolExecutor.class);
        humanInteractionManager = new HumanInteractionManager();
        toolRegistry = mock(ToolRegistry.class);

        SkillProperties properties = new SkillProperties();
        properties.setStorageDir(Path.of(System.getProperty("java.io.tmpdir"), "skill-integ-" + System.nanoTime()).toString());
        skillStore = new SkillStore(properties);
        skillSessionManager = new SkillSessionManager(skillStore, properties);

        AgentConfig agentConfig = new AgentConfig();
        agentConfig.setEnableLogging(false);
        agentConfig.getTools().setDefaultTools(List.of());
        SkillScriptToolRegistrar registrar = new SkillScriptToolRegistrar(toolRegistry, new SkillScriptExecutor());
        sessionToolResolver = new SessionToolResolver(toolRegistry, agentConfig, skillSessionManager, registrar);

        SkillLoadTool skillLoadTool = new SkillLoadTool(skillStore, skillSessionManager, properties);
        ToolSchemaConverter converter = mock(ToolSchemaConverter.class);
        when(converter.convertToJson(any())).thenReturn("[tools-json]");
        interceptor = new SkillToolInterceptorImpl(skillLoadTool, skillSessionManager, sessionToolResolver, converter);

        // 预置技能：数据查询助手（自带 http-get 脚本工具，CR-001）
        SkillDefinition dataQuery = new SkillDefinition();
        dataQuery.setId("data-query-assistant");
        dataQuery.setName("数据查询助手");
        dataQuery.setDescription("查询数据");
        dataQuery.setInstruction("优先使用自带脚本工具查询数据");
        dataQuery.setScripts(List.of(new SkillScript("http-get", "shell", "通过 HTTP GET 获取公开数据",
                List.of(new ScriptParam("url", "string", true, "目标 URL")),
                "curl -s \"$SKILL_PARAM_URL\"")));
        skillStore.create(dataQuery);
    }

    private ToolCall toolCall(String id, String name, String args) {
        ToolCall tc = new ToolCall();
        tc.setId(id);
        tc.setFunctionName(name);
        tc.setArguments(args);
        return tc;
    }

    private HITLReActStream buildStream(List<ChatMessage> messages) {
        return new HITLReActStream(model, messages, "[tools]", toolExecutor,
                humanInteractionManager, "sess", "model", 0, 10, interceptor);
    }

    @Test
    void shouldActivateSkillAndRefreshToolsForSameTurnScriptToolUse() {
        // 第一轮：loadSkill；第二轮：skill_data_query_assistant_http_get（脚本工具，热刷新后可用）；第三轮：stop
        AtomicInteger round = new AtomicInteger(0);
        doAnswer(inv -> {
            ThinkingStreamHandler handler = inv.getArgument(2);
            switch (round.getAndIncrement()) {
                case 0 -> {
                    handler.onPartialResponse("激活技能");
                    handler.onToolCalls(List.of(toolCall("c1", "loadSkill", "{\"skillName\":\"data-query-assistant\"}")));
                    handler.onComplete("激活技能", "tool_calls", null);
                }
                case 1 -> {
                    handler.onPartialResponse("调用脚本工具");
                    handler.onToolCalls(List.of(toolCall("c2", SCRIPT_TOOL, "{\"url\":\"https://example.com/data\"}")));
                    handler.onComplete("调用脚本工具", "tool_calls", null);
                }
                default -> {
                    handler.onPartialResponse("已获取数据");
                    handler.onComplete("已获取数据", "stop", null);
                }
            }
            return null;
        }).when(model).stream(any(), anyString(), any());

        // 脚本工具默认 ALLOW 自主执行（AC-T03：不进入权限确认流）
        when(toolExecutor.checkPermission(anyString()))
                .thenReturn(new ToolExecutor.ToolPermissionCheck(ToolPermissionLevel.ALLOW, null, null));
        when(toolExecutor.execute(SCRIPT_TOOL, "{\"url\":\"https://example.com/data\"}")).thenReturn("排行榜数据");

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("系统提示"));
        messages.add(UserMessage.from("帮我查数据"));

        HitlTokenStream.SkillActivatedConsumer skillConsumer = mock(HitlTokenStream.SkillActivatedConsumer.class);
        HITLReActStream stream = buildStream(messages);
        stream.onSkillActivated(skillConsumer);
        stream.start();

        // 激活事件触发（AC-S04），来源 AUTO，载荷=自带脚本工具名（CR-001）
        verify(skillConsumer).accept("data-query-assistant", "数据查询助手", "AUTO",
                List.of(ScriptToolToolName()));
        // 脚本工具被执行（当轮热刷新后可用，AC-T02）
        verify(toolExecutor).execute(SCRIPT_TOOL, "{\"url\":\"https://example.com/data\"}");
        // 最终回答
        assertThat(messages).anyMatch(m -> m instanceof dev.langchain4j.data.message.AiMessage);
    }

    private String ScriptToolToolName() {
        return "skill_data_query_assistant_http_get";
    }

    @Test
    void shouldExecuteScriptToolWithoutToolConfirm() {
        // 脚本工具不进入系统权限确认流（AC-T03）：直接自主执行，不触发 tool_confirm
        AtomicInteger round = new AtomicInteger(0);
        doAnswer(inv -> {
            ThinkingStreamHandler handler = inv.getArgument(2);
            switch (round.getAndIncrement()) {
                case 0 -> {
                    handler.onPartialResponse("激活");
                    handler.onToolCalls(List.of(toolCall("c1", "loadSkill", "{\"skillName\":\"data-query-assistant\"}")));
                    handler.onComplete("激活", "tool_calls", null);
                }
                case 1 -> {
                    handler.onPartialResponse("调用");
                    handler.onToolCalls(List.of(toolCall("c2", SCRIPT_TOOL, "{\"url\":\"https://example.com\"}")));
                    handler.onComplete("调用", "tool_calls", null);
                }
                default -> {
                    handler.onPartialResponse("完成");
                    handler.onComplete("完成", "stop", null);
                }
            }
            return null;
        }).when(model).stream(any(), anyString(), any());

        when(toolExecutor.checkPermission(anyString()))
                .thenReturn(new ToolExecutor.ToolPermissionCheck(ToolPermissionLevel.ALLOW, null, null));
        when(toolExecutor.execute(SCRIPT_TOOL, "{\"url\":\"https://example.com\"}")).thenReturn("数据");

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("系统提示"));
        messages.add(UserMessage.from("帮我查数据"));

        HitlTokenStream.ToolConfirmConsumer confirmConsumer = mock(HitlTokenStream.ToolConfirmConsumer.class);
        HITLReActStream stream = buildStream(messages);
        stream.onToolConfirm(confirmConsumer);
        stream.start();

        // 脚本工具已执行，未触发确认卡片
        verify(toolExecutor).execute(SCRIPT_TOOL, "{\"url\":\"https://example.com\"}");
        verify(confirmConsumer, org.mockito.Mockito.never()).accept(any(), any(), any(), any());
    }

    @Test
    void shouldDegradeWhenScriptToolFailsWithoutBreakingSkill() {
        // 脚本工具执行失败（AC-T04）：错误观察值回填，循环继续，技能其余增强不失效
        AtomicInteger round = new AtomicInteger(0);
        doAnswer(inv -> {
            ThinkingStreamHandler handler = inv.getArgument(2);
            switch (round.getAndIncrement()) {
                case 0 -> {
                    handler.onPartialResponse("激活");
                    handler.onToolCalls(List.of(toolCall("c1", "loadSkill", "{\"skillName\":\"data-query-assistant\"}")));
                    handler.onComplete("激活", "tool_calls", null);
                }
                case 1 -> {
                    handler.onPartialResponse("调用");
                    handler.onToolCalls(List.of(toolCall("c2", SCRIPT_TOOL, "{\"url\":\"https://example.com\"}")));
                    handler.onComplete("调用", "tool_calls", null);
                }
                default -> {
                    handler.onPartialResponse("工具失败，我换个方式");
                    handler.onComplete("工具失败，我换个方式", "stop", null);
                }
            }
            return null;
        }).when(model).stream(any(), anyString(), any());

        when(toolExecutor.checkPermission(anyString()))
                .thenReturn(new ToolExecutor.ToolPermissionCheck(ToolPermissionLevel.ALLOW, null, null));
        when(toolExecutor.execute(anyString(), anyString())).thenReturn("脚本执行失败: 网络超时");

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("系统提示"));
        messages.add(UserMessage.from("查数据"));

        HITLReActStream stream = buildStream(messages);
        stream.start();

        // 失败观察值已回填（不中断循环，AC-T04）
        assertThat(messages).anyMatch(m -> m instanceof ToolExecutionResultMessage
                && ((ToolExecutionResultMessage) m).text().contains("网络超时"));
        // 技能仍处于激活态（其余增强不失效）
        assertThat(skillSessionManager.getActiveSkillIds("sess")).containsExactly("data-query-assistant");
    }
}
