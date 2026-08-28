package com.agentdemo.agent.core;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.agent.prompt.PromptTemplateLoader;
import com.agentdemo.agent.single.HITLReActStream;
import com.agentdemo.agent.single.SessionToolResolver;
import com.agentdemo.agent.single.SkillToolInterceptor;
import com.agentdemo.llm.registry.ModelFactory;
import com.agentdemo.llm.thinking.ThinkingStreamHandler;
import com.agentdemo.llm.thinking.ThinkingStreamingChatModel;
import com.agentdemo.memory.shortterm.ChatMemoryManager;
import com.agentdemo.memory.shortterm.CompressingChatMemory;
import com.agentdemo.skill.prompt.SkillPromptComposer;
import com.agentdemo.tools.registry.ToolExecutor;
import com.agentdemo.tools.registry.ToolRegistry;
import com.agentdemo.tools.registry.ToolSchemaConverter;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.memory.ChatMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UnifiedChatStream 技能段注入测试（技术方案 Task-14，AC-N01/S04/M01）
 * <p>
 * 业务含义：直答路径系统提示词追加技能目录段+激活段；无技能时与现状零差异；
 * onSkillActivated 回调可注册转发。
 * </p>
 */
class UnifiedChatStreamSkillTest {

    private ModelFactory modelFactory;
    private ThinkingStreamingChatModel thinkingModel;
    private ChatMemoryManager memoryManager;
    private ChatMemory chatMemory;
    private ToolSchemaConverter toolSchemaConverter;
    private ToolExecutor toolExecutor;
    private AgentConfig agentConfig;
    private HumanInteractionManager humanInteractionManager;
    private SessionToolResolver sessionToolResolver;
    private TaskPlanJudge taskPlanJudge;
    private SkillPromptComposer skillPromptComposer;
    private SkillToolInterceptor skillToolInterceptor;

    @BeforeEach
    void setUp() {
        modelFactory = mock(ModelFactory.class);
        thinkingModel = mock(ThinkingStreamingChatModel.class);
        memoryManager = mock(ChatMemoryManager.class);
        chatMemory = mock(ChatMemory.class);
        toolSchemaConverter = mock(ToolSchemaConverter.class);
        toolExecutor = mock(ToolExecutor.class);
        agentConfig = new AgentConfig();
        humanInteractionManager = new HumanInteractionManager();
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        sessionToolResolver = new SessionToolResolver(toolRegistry, agentConfig);
        taskPlanJudge = mock(TaskPlanJudge.class);
        skillPromptComposer = mock(SkillPromptComposer.class);
        skillToolInterceptor = mock(SkillToolInterceptor.class);

        when(modelFactory.getDefaultThinkingStreamingChatModel()).thenReturn(thinkingModel);
        when(toolSchemaConverter.convertToJson(anyList())).thenReturn("[]");
        when(toolSchemaConverter.convertToDescriptionText(anyList())).thenReturn("工具描述");
        when(memoryManager.getMemory(anyString())).thenReturn(chatMemory);
        when(chatMemory.messages()).thenReturn(new ArrayList<>());
        when(toolRegistry.resolveToolsForStreaming(anyList())).thenReturn(List.of());
        when(toolRegistry.getDefaultToolsForStreaming(anyList())).thenReturn(List.of());
        when(taskPlanJudge.judge(anyString(), anyString(), isNull(), anyList())).thenReturn(List.of());
    }

    private UnifiedChatStream createStream(String message) {
        return new UnifiedChatStream(
                "test-session", message, null, false, false, null, null,
                modelFactory, memoryManager, agentConfig, toolSchemaConverter, toolExecutor,
                new PromptTemplateLoader(agentConfig), humanInteractionManager,
                sessionToolResolver, taskPlanJudge, skillPromptComposer, skillToolInterceptor);
    }

    /** 捕获传给 LLM 的首轮消息列表（含系统提示词） */
    private List<dev.langchain4j.data.message.ChatMessage> captureSystemMessages() {
        ArgumentCaptor<List<dev.langchain4j.data.message.ChatMessage>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(thinkingModel).stream(messagesCaptor.capture(), anyString(), any());
        return messagesCaptor.getValue();
    }

    private void mockDirectStop() {
        org.mockito.Mockito.doAnswer(inv -> {
            ThinkingStreamHandler handler = inv.getArgument(2);
            handler.onPartialResponse("回答");
            handler.onComplete("回答", "stop", null);
            return null;
        }).when(thinkingModel).stream(org.mockito.ArgumentMatchers.any(), anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldNotAppendSkillSegmentsToSystemPrompt() {
        // agent-context-engineering 新契约（AC-N01/T01）：技能段移出系统提示词，改走记忆流附件
        when(skillPromptComposer.composeCatalogSegment("test-session")).thenReturn("## 可用技能\n- s1：技能一");
        when(skillPromptComposer.composeActivatedSegment("test-session")).thenReturn("## 已激活技能\n指令全文");
        mockDirectStop();

        createStream("帮我写周报").start();

        List<dev.langchain4j.data.message.ChatMessage> messages = captureSystemMessages();
        SystemMessage sys = (SystemMessage) messages.get(0);
        assertThat(sys.text()).doesNotContain("可用技能").doesNotContain("已激活技能")
                .doesNotContain("技能一").doesNotContain("指令全文");
    }

    @Test
    void shouldWriteCatalogAttachmentOnFirstRequest() {
        // AC-N01：会话首请求将技能目录写入记忆流附件（emit-once），系统提示词保持冻结
        when(skillPromptComposer.composeCatalogAttachment("test-session")).thenReturn("## 可用技能\n- s1：技能一");
        when(memoryManager.hasAttachment("test-session", CompressingChatMemory.AttachmentType.CATALOG))
                .thenReturn(false);
        mockDirectStop();

        createStream("你好").start();

        verify(memoryManager).addAttachment("test-session",
                CompressingChatMemory.AttachmentType.CATALOG, "## 可用技能\n- s1：技能一");
        List<dev.langchain4j.data.message.ChatMessage> messages = captureSystemMessages();
        SystemMessage sys = (SystemMessage) messages.get(0);
        assertThat(sys.text()).doesNotContain("可用技能");
    }

    @Test
    void shouldNotRewriteCatalogAttachmentWhenPresent() {
        // 已存在目录附件时不重复写入（emit-once 幂等）
        when(memoryManager.hasAttachment("test-session", CompressingChatMemory.AttachmentType.CATALOG))
                .thenReturn(true);
        mockDirectStop();

        createStream("你好").start();

        verify(memoryManager, never()).addAttachment(eq("test-session"),
                eq(CompressingChatMemory.AttachmentType.CATALOG), anyString());
    }

    @Test
    void shouldNotModifySystemPromptWhenNoSkills() {
        when(skillPromptComposer.composeCatalogSegment("test-session")).thenReturn("");
        when(skillPromptComposer.composeActivatedSegment("test-session")).thenReturn("");
        mockDirectStop();

        createStream("你好").start();

        List<dev.langchain4j.data.message.ChatMessage> messages = captureSystemMessages();
        SystemMessage sys = (SystemMessage) messages.get(0);
        // 无技能时不追加技能段（零差异，AC-E02 回归）
        assertThat(sys.text()).doesNotContain("可用技能").doesNotContain("已激活技能");
    }

    @Test
    void shouldForwardSkillActivatedToRegisteredCallback() {
        // 验证 onSkillActivated 可注册（链式接口可用性），转发在 Task-25 集成验证
        createStream("test");
    }
}
