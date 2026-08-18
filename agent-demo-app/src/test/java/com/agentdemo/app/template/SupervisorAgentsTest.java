package com.agentdemo.app.template;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.TokenStream;
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
 * Supervisor Agent 接口与提示词模板测试（P3 Task-12）
 * <p>
 * 业务含义：主控拆解/汇总 Agent 按既有 9 接口固定模式声明（@Agent + @V + TokenStream），
 * 场景提示词约束 LLM 输出为 JSON 子任务数组（AC-007）。
 * </p>
 */
class SupervisorAgentsTest {

    @Test
    void planAgent_shouldHaveAgentAnnotatedMethodWithTaskVariable() {
        Method method = findAgentMethod(SupervisorPlanAgent.class);
        assertNotNull(method, "SupervisorPlanAgent 缺少 @Agent 方法");
        assertEquals(TokenStream.class, method.getReturnType());

        Parameter param = method.getParameters()[0];
        assertTrue(param.isAnnotationPresent(V.class), "参数缺少 @V 注解");
        assertEquals("task", param.getAnnotation(V.class).value());
    }

    @Test
    void summarizeAgent_shouldHaveAgentAnnotatedMethodWithResultsVariable() {
        Method method = findAgentMethod(SupervisorSummarizeAgent.class);
        assertNotNull(method, "SupervisorSummarizeAgent 缺少 @Agent 方法");
        assertEquals(TokenStream.class, method.getReturnType());

        Parameter param = method.getParameters()[0];
        assertTrue(param.isAnnotationPresent(V.class), "参数缺少 @V 注解");
        assertEquals("results", param.getAnnotation(V.class).value());
    }

    @Test
    void planPrompt_shouldContainWorkerListAndJsonFormatConstraint() {
        String content = readPrompt("prompts/scenarios/app-supervisor-plan.txt");
        assertTrue(content.contains("研究"), "拆解提示词缺少 Worker 清单: 研究");
        assertTrue(content.contains("分析"), "拆解提示词缺少 Worker 清单: 分析");
        assertTrue(content.contains("总结"), "拆解提示词缺少 Worker 清单: 总结");
        assertTrue(content.contains("[") && content.contains("]"),
                "拆解提示词缺少 JSON 数组格式说明");
        assertTrue(content.contains("description") && content.contains("agent"),
                "拆解提示词缺少子任务字段说明（description/agent）");
    }

    @Test
    void summarizePrompt_shouldExistAndBeNonEmpty() {
        assertFalse(readPrompt("prompts/scenarios/app-supervisor-summarize.txt").isBlank(),
                "汇总提示词文件为空");
    }

    private Method findAgentMethod(Class<?> clazz) {
        for (Method method : clazz.getMethods()) {
            if (method.isAnnotationPresent(Agent.class)) {
                return method;
            }
        }
        return null;
    }

    private String readPrompt(String path) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(is, "提示词文件不存在: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("读取提示词文件失败: " + path, e);
        }
    }
}
