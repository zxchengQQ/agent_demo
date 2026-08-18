package com.agentdemo.app.template;

import com.agentdemo.app.core.AgentDefinition;
import com.agentdemo.app.core.OrchestrationMode;
import com.agentdemo.app.core.WorkflowTemplate;
import com.agentdemo.app.registry.WorkflowTemplateRegistry;
import com.agentdemo.tools.registry.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * 预置模板测试（Task-12）
 */
class ResearchAnalyzeSummarizeTemplateTest {

    private WorkflowTemplateRegistry registry;
    private ToolRegistry toolRegistry;
    private ResearchAnalyzeSummarizeTemplate config;

    @BeforeEach
    void setUp() {
        registry = new WorkflowTemplateRegistry();
        toolRegistry = mock(ToolRegistry.class);
        config = new ResearchAnalyzeSummarizeTemplate(toolRegistry);
    }

    @Test
    void template_shouldRegisterAndBeQueryable() {
        config.researchAnalyzeSummarizeWorkflow(registry);
        WorkflowTemplate template = registry.getTemplate("research-analyze-summarize");
        assertNotNull(template);
    }

    @Test
    void template_shouldHaveCorrectMeta() {
        WorkflowTemplate template = config.researchAnalyzeSummarizeWorkflow(registry);
        assertEquals("研究-分析-总结", template.getName());
        assertEquals(OrchestrationMode.SEQUENTIAL, template.getMode());
        assertEquals(3, template.getMaxRetries());
        assertEquals(3, template.getAgents().size());
        assertEquals("研究 Agent", template.getAgents().get(0).getName());
        assertEquals("分析 Agent", template.getAgents().get(1).getName());
        assertEquals("总结 Agent", template.getAgents().get(2).getName());
    }

    @Test
    void researchAgent_shouldHaveHttpGetToolAndInterface() {
        WorkflowTemplate template = config.researchAnalyzeSummarizeWorkflow(registry);
        AgentDefinition research = template.getAgents().get(0);
        assertTrue(research.getToolIds().contains("builtin:httpGet"));
        assertEquals(ResearchAgent.class, research.getInterfaceClass());
    }

    @Test
    void template_shouldHaveOneRequiredParameter() {
        WorkflowTemplate template = config.researchAnalyzeSummarizeWorkflow(registry);
        assertEquals(1, template.getParameters().size());
        assertEquals("topic", template.getParameters().get(0).getName());
        assertTrue(template.getParameters().get(0).isRequired());
    }

    @Test
    void template_shouldNotBlockWhenToolMissing() {
        // 工具未注册：resolveTools 抛异常 → 应记录 WARNING 但不阻断注册
        doThrow(new RuntimeException("工具不存在")).when(toolRegistry).resolveTools(anyList());
        config.researchAnalyzeSummarizeWorkflow(registry);
        assertNotNull(registry.getTemplate("research-analyze-summarize"));
    }
}
