package com.agentdemo.tools.builtin;

import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AskUserTool 单元测试
 * <p>
 * 验证标准来源：Task-04 验证标准
 * 业务含义：验证 askUser 工具的 @Component 注册、@Tool 注解描述、参数签名正确性。
 * </p>
 */
class AskUserToolTest {

    @Test
    void 类标注_Component() {
        assertNotNull(AskUserTool.class.getAnnotation(org.springframework.stereotype.Component.class),
                "AskUserTool 必须标注 @Component 以被 ToolRegistry 自动扫描");
    }

    @Test
    void askUser方法标注_Tool且描述包含使用引导() {
        Method method = findAskUserMethod();
        Tool toolAnnotation = method.getAnnotation(Tool.class);
        assertNotNull(toolAnnotation, "askUser 方法必须标注 @Tool");

        String description = String.join(" ", toolAnnotation.value());
        assertTrue(description.contains("追问") || description.contains("确认") || description.contains("askUser"),
                "工具描述应包含使用场景引导");
        assertTrue(description.contains("不适用"), "工具描述应包含不适用场景");
    }

    @Test
    void askUser方法参数为_type_question_options() {
        Method method = findAskUserMethod();
        Parameter[] params = method.getParameters();

        assertEquals(3, params.length, "askUser 方法应有 3 个参数");
        assertEquals("type", params[0].getName(), "第一个参数应为 type");
        assertEquals("question", params[1].getName(), "第二个参数应为 question");
        assertEquals("options", params[2].getName(), "第三个参数应为 options");
    }

    @Test
    void askUser方法返回_String() {
        Method method = findAskUserMethod();
        assertEquals(String.class, method.getReturnType(), "askUser 方法应返回 String");
    }

    @Test
    void askUser方法调用返回占位文本() {
        AskUserTool tool = new AskUserTool();
        String result = tool.askUser("text", "请提供订单号", new String[]{"选项1", "选项2"});
        assertNotNull(result);
        assertFalse(result.isEmpty(), "占位返回不应为空");
    }

    /**
     * 查找 askUser 方法
     */
    private Method findAskUserMethod() {
        return Arrays.stream(AskUserTool.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Tool.class))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到标注 @Tool 的方法"));
    }
}
