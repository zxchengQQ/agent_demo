package com.agentdemo.app.adapter;

import com.agentdemo.app.core.AgentDefinition;
import com.agentdemo.app.template.ResearchAgent;
import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.llm.registry.ModelFactory;
import com.agentdemo.agent.prompt.PromptTemplateLoader;
import com.agentdemo.tools.registry.ToolRegistry;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 构建工厂测试（Task-06）
 */
class AgenticAgentFactoryTest {

    private ModelFactory modelFactory;
    private ToolRegistry toolRegistry;
    private PromptTemplateLoader promptTemplateLoader;
    private AgenticAgentFactory factory;

    @BeforeEach
    void setUp() {
        modelFactory = mock(ModelFactory.class);
        toolRegistry = mock(ToolRegistry.class);
        promptTemplateLoader = mock(PromptTemplateLoader.class);
        factory = new AgenticAgentFactory(modelFactory, toolRegistry, promptTemplateLoader);
    }

    private AgentDefinition agentDef(String modelId, List<String> toolIds) {
        return AgentDefinition.builder()
                .name("研究 Agent")
                .description("收集信息")
                .modelId(modelId)
                .toolIds(toolIds)
                .roleName("general")
                .scenarioName("app-research")
                .interfaceClass(ResearchAgent.class)
                .build();
    }

    @Test
    void buildAgent_shouldReturnNonNullProxy() {
        StreamingChatModel model = mock(StreamingChatModel.class);
        when(modelFactory.getDefaultStreamingChatModel()).thenReturn(model);
        Object agent = factory.buildAgent(agentDef(null, List.of()));
        assertNotNull(agent);
    }

    @Test
    void buildAgent_shouldUseDefaultModelWhenModelIdNull() {
        StreamingChatModel model = mock(StreamingChatModel.class);
        when(modelFactory.getDefaultStreamingChatModel()).thenReturn(model);
        factory.buildAgent(agentDef(null, List.of()));
        verify(modelFactory).getDefaultStreamingChatModel();
        verify(modelFactory, never()).getStreamingChatModelByModelId(any());
    }

    @Test
    void buildAgent_shouldUseModelByModelIdWhenSpecified() {
        StreamingChatModel model = mock(StreamingChatModel.class);
        when(modelFactory.getStreamingChatModelByModelId("vendor1:model1")).thenReturn(model);
        factory.buildAgent(agentDef("vendor1:model1", List.of()));
        verify(modelFactory).getStreamingChatModelByModelId("vendor1:model1");
    }

    @Test
    void buildAgent_shouldNotResolveToolsWhenToolIdsEmpty() {
        StreamingChatModel model = mock(StreamingChatModel.class);
        when(modelFactory.getDefaultStreamingChatModel()).thenReturn(model);
        factory.buildAgent(agentDef(null, List.of()));
        verify(toolRegistry, never()).resolveTools(any());
    }

    @Test
    void buildAgent_shouldResolveToolsWhenToolIdsPresent() {
        StreamingChatModel model = mock(StreamingChatModel.class);
        when(modelFactory.getDefaultStreamingChatModel()).thenReturn(model);
        when(toolRegistry.resolveTools(List.of("builtin:httpGet"))).thenReturn(List.of(new HttpGetTool()));
        factory.buildAgent(agentDef(null, List.of("builtin:httpGet")));
        verify(toolRegistry).resolveTools(List.of("builtin:httpGet"));
    }

    @Test
    void buildAgent_shouldThrowWorkflowModelNotFoundWhenModelMissing() {
        when(modelFactory.getStreamingChatModelByModelId("missing:model"))
                .thenThrow(new BusinessException(ErrorCode.LLM_MODEL_NOT_FOUND, "模型不存在"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> factory.buildAgent(agentDef("missing:model", List.of())));
        assertEquals(ErrorCode.WORKFLOW_MODEL_NOT_FOUND, ex.getErrorCode());
    }

    /** 测试工具类：含 @Tool 方法，用于验证工具解析 */
    public static class HttpGetTool {
        @dev.langchain4j.agent.tool.Tool
        public String httpGet(String url) {
            return "ok";
        }
    }
}
