package com.agentdemo.app.template;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 接口与提示词模板测试（Task-11）
 */
class AgentInterfaceTest {

    @Test
    void researchAgent_shouldHaveAgentAnnotatedMethodReturningTokenStream() {
        Method method = findAgentMethod(ResearchAgent.class);
        assertNotNull(method);
        assertTrue(method.isAnnotationPresent(Agent.class));
        assertTrue(method.isAnnotationPresent(UserMessage.class));
        assertEquals(TokenStream.class, method.getReturnType());
    }

    @Test
    void researchAgent_execute_shouldHaveVAnnotation() {
        Method method = findAgentMethod(ResearchAgent.class);
        Parameter param = method.getParameters()[0];
        assertTrue(param.isAnnotationPresent(V.class));
        assertEquals("topic", param.getAnnotation(V.class).value());
    }

    @Test
    void analysisAgent_execute_shouldHaveVAnnotation() {
        Method method = findAgentMethod(AnalysisAgent.class);
        assertNotNull(method);
        assertTrue(method.isAnnotationPresent(Agent.class));
        Parameter param = method.getParameters()[0];
        assertTrue(param.isAnnotationPresent(V.class));
        assertEquals("researchResult", param.getAnnotation(V.class).value());
    }

    @Test
    void summaryAgent_execute_shouldHaveVAnnotation() {
        Method method = findAgentMethod(SummaryAgent.class);
        assertNotNull(method);
        assertTrue(method.isAnnotationPresent(Agent.class));
        Parameter param = method.getParameters()[0];
        assertTrue(param.isAnnotationPresent(V.class));
        assertEquals("analysisResult", param.getAnnotation(V.class).value());
    }

    @Test
    void promptTemplates_shouldExistAndBeNonEmpty() {
        assertPromptNonEmpty("prompts/scenarios/app-research.txt");
        assertPromptNonEmpty("prompts/scenarios/app-analysis.txt");
        assertPromptNonEmpty("prompts/scenarios/app-summary.txt");
    }

    @Test
    void p2ScoringAgent_shouldHaveAgentAnnotatedMethod() {
        Method method = findAgentMethod(ScoringAgent.class);
        assertNotNull(method);
        assertTrue(method.isAnnotationPresent(Agent.class));
        assertTrue(method.isAnnotationPresent(UserMessage.class));
        assertEquals(TokenStream.class, method.getReturnType());
        assertTrue(method.getParameters()[0].isAnnotationPresent(V.class));
    }

    @Test
    void p2ReviseAgent_shouldHaveAgentAnnotatedMethod() {
        Method method = findAgentMethod(ReviseAgent.class);
        assertNotNull(method);
        assertTrue(method.isAnnotationPresent(Agent.class));
        assertEquals(TokenStream.class, method.getReturnType());
    }

    @Test
    void p2ReviewAgents_shouldHaveAgentAnnotatedMethods() {
        assertNotNull(findAgentMethod(SecurityReviewAgent.class));
        assertNotNull(findAgentMethod(PerformanceReviewAgent.class));
        assertNotNull(findAgentMethod(StyleReviewAgent.class));
        assertNotNull(findAgentMethod(QuickAnswerAgent.class));
    }

    @Test
    void p2PromptTemplates_shouldExistAndBeNonEmpty() {
        assertPromptNonEmpty("prompts/scenarios/app-scoring.txt");
        assertPromptNonEmpty("prompts/scenarios/app-revise.txt");
        assertPromptNonEmpty("prompts/scenarios/app-security-review.txt");
        assertPromptNonEmpty("prompts/scenarios/app-performance-review.txt");
        assertPromptNonEmpty("prompts/scenarios/app-style-review.txt");
        assertPromptNonEmpty("prompts/scenarios/app-quick-answer.txt");
    }

    private Method findAgentMethod(Class<?> clazz) {
        for (Method method : clazz.getMethods()) {
            if (method.isAnnotationPresent(Agent.class)) {
                return method;
            }
        }
        return null;
    }

    private void assertPromptNonEmpty(String path) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(is, "提示词文件不存在: " + path);
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(content.isBlank(), "提示词文件为空: " + path);
        } catch (Exception e) {
            throw new AssertionError("读取提示词文件失败: " + path, e);
        }
    }
}
