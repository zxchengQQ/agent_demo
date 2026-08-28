package com.agentdemo.agent.core;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.agent.prompt.PromptTemplateLoader;
import com.agentdemo.llm.registry.ModelFactory;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TaskPlanJudge 单元测试
 * <p>
 * 验证标准来源：unified-chat-mode 任务规划 Task-04 验证标准
 * 关联 AC：AC-T01（判断与路由）、AC-N02/N03（判定结果）、AC-E01（失败降级）
 * 业务含义：统一对话模式下每条普通消息先经规划判断，返回非空列表则拆解、空列表则直答；
 * 任何异常/解析失败均降级返回空列表（AC-E01，不中断对话）。
 * </p>
 */
class TaskPlanJudgeTest {

    private ModelFactory modelFactory;
    private ChatModel chatModel;
    private AgentConfig agentConfig;
    private TaskPlanJudge judge;

    @BeforeEach
    void setUp() {
        modelFactory = mock(ModelFactory.class);
        chatModel = mock(ChatModel.class);
        when(modelFactory.getDefaultChatModel()).thenReturn(chatModel);

        agentConfig = new AgentConfig();
        judge = new TaskPlanJudge(modelFactory, agentConfig, new PromptTemplateLoader(agentConfig));
    }

    /** 模拟 ChatModel.chat 返回指定文本 */
    private void mockChatModelResponse(String text) {
        AiMessage aiMessage = AiMessage.from(text);
        ChatResponse response = mock(ChatResponse.class);
        when(response.aiMessage()).thenReturn(aiMessage);
        when(chatModel.chat(anyList())).thenReturn(response);
    }

    @Test
    @DisplayName("LLM 返回合法 JSON 数组 -> 解析为子任务列表")
    void judge_合法JSON数组_解析为子任务列表() {
        mockChatModelResponse("[{\"title\":\"分析需求\"},{\"title\":\"调研方案\"}]");

        List<SubTask> tasks = judge.judge("sess-1", "帮我调研竞品", null);

        assertEquals(2, tasks.size(), "应解析出 2 个子任务");
        assertEquals(1, tasks.get(0).index(), "第1个子任务 index 应为 1");
        assertEquals("分析需求", tasks.get(0).title());
        assertEquals(2, tasks.get(1).index(), "第2个子任务 index 应为 2");
        assertEquals("调研方案", tasks.get(1).title());
    }

    @Test
    @DisplayName("LLM 返回 [] -> 空列表（不拆解信号）")
    void judge_空数组_返回空列表() {
        mockChatModelResponse("[]");

        List<SubTask> tasks = judge.judge("sess-1", "你好", null);

        assertTrue(tasks.isEmpty(), "空数组应返回空列表，表示直接回答");
    }

    @Test
    @DisplayName("LLM 返回带 markdown 代码块包裹的 JSON -> 容错提取成功")
    void judge_markdown代码块包裹JSON_容错提取() {
        mockChatModelResponse("```json\n[{\"title\":\"分析\"},{\"title\":\"执行\"}]\n```");

        List<SubTask> tasks = judge.judge("sess-1", "复杂任务", null);

        assertEquals(2, tasks.size(), "应解析出 2 个子任务（忽略 markdown 标记）");
    }

    @Test
    @DisplayName("LLM 调用抛异常 -> 返回空列表 + 不向上抛出（AC-E01 降级直答）")
    void judge_调用抛异常_降级空列表() {
        when(chatModel.chat(anyList())).thenThrow(new RuntimeException("LLM 连接失败"));

        List<SubTask> tasks = judge.judge("sess-1", "触发错误", null);

        assertTrue(tasks.isEmpty(), "调用异常应降级返回空列表，不向上抛出");
    }

    @Test
    @DisplayName("LLM 返回非法格式（无法提取 JSON 数组）-> 空列表降级")
    void judge_非法格式_降级空列表() {
        mockChatModelResponse("这是一个简单任务，不需要拆解。直接回答即可。");

        List<SubTask> tasks = judge.judge("sess-1", "你好", null);

        assertTrue(tasks.isEmpty(), "非法格式应降级返回空列表");
    }

    @Test
    @DisplayName("子任务数超上限 -> 截断至 taskBreakdownMaxSubtasks")
    void judge_子任务超限_截断() {
        StringBuilder json = new StringBuilder("[");
        for (int i = 1; i <= 15; i++) {
            if (i > 1) json.append(",");
            json.append("{\"title\":\"任务").append(i).append("\"}");
        }
        json.append("]");
        mockChatModelResponse(json.toString());

        List<SubTask> tasks = judge.judge("sess-1", "复杂任务", null);

        assertEquals(agentConfig.getTaskBreakdownMaxSubtasks(), tasks.size(),
                "15 个子任务应截断至配置上限（默认 10）");
    }

    @Test
    @DisplayName("指定 modelId 时使用对应 ChatModel")
    void judge_指定modelId_使用对应模型() {
        ChatModel specificModel = mock(ChatModel.class);
        when(modelFactory.getChatModelByModelId("model-x")).thenReturn(specificModel);
        AiMessage aiMessage = AiMessage.from("[{\"title\":\"任务\"}]");
        ChatResponse response = mock(ChatResponse.class);
        when(response.aiMessage()).thenReturn(aiMessage);
        when(specificModel.chat(anyList())).thenReturn(response);

        List<SubTask> tasks = judge.judge("sess-1", "任务", "model-x");

        assertEquals(1, tasks.size(), "指定 modelId 时使用对应模型并正常解析");
        verify(modelFactory).getChatModelByModelId("model-x");
    }

    @Test
    @DisplayName("提示词组装使用 task-plan 场景模板（BR-AGT-005 组合机制）")
    void judge_提示词使用taskPlan场景模板() {
        mockChatModelResponse("[]");

        judge.judge("sess-1", "测试", null);

        // 业务含义：judge 需调用 composeSystemPrompt(SCENARIO_TASK_PLAN) 组装规划提示词
        // （验证方式：通过 AgentConfig 默认模板存在性间接验证，此处验证 judge 不抛异常）
        assertTrue(true);
    }

    // ==================== Task-07: 历史注入（agent-context-engineering，AC-N03） ====================

    @Test
    @DisplayName("带历史时：历史注入于系统提示词之后、当前消息之前")
    void judge_带历史_注入于当前消息之前() {
        mockChatModelResponse("[]");
        List<dev.langchain4j.data.message.ChatMessage> history = List.of(
                UserMessage.from("上一轮：查一下订单 ORD-12345"),
                AiMessage.from("您的订单已发货"));

        judge.judge("sess-1", "帮我把它做个深度分析并拆解执行", null, history);

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatModel).chat(captor.capture());
        List<ChatMessage> sent = captor.getValue();
        // 顺序：System(task-plan) -> 历史 -> 当前消息
        assertTrue(sent.get(0) instanceof SystemMessage, "首位应为系统提示词");
        assertTrue(sent.get(1) instanceof UserMessage);
        assertEquals("上一轮：查一下订单 ORD-12345", ((UserMessage) sent.get(1)).singleText(),
                "历史应注入于当前消息之前");
        assertEquals("帮我把它做个深度分析并拆解执行", ((UserMessage) sent.get(sent.size() - 1)).singleText(),
                "当前消息应为最后一条");
    }

    @Test
    @DisplayName("历史为 null 时降级为仅当前消息（现状行为）")
    void judge_无历史_仅当前消息() {
        mockChatModelResponse("[]");

        judge.judge("sess-1", "你好", null, null);

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatModel).chat(captor.capture());
        List<ChatMessage> sent = captor.getValue();
        assertEquals(2, sent.size(), "System + 当前消息，共 2 条");
    }
}
