package com.agentdemo.app.core;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @HumanCheckpoint 注解测试（工作流 HITL Task-01）
 * <p>
 * 业务含义：验证模板预设检查点注解的定义约束——仅可标注在方法上（@Target(METHOD)）、
 * 运行时反射可读取（@Retention(RUNTIME)）、支持默认/显式确认提示语。
 * AgentExecutor 据此在方法执行前暂停等待用户确认（AC-N02/AC-T02 前置）。
 * </p>
 */
class HumanCheckpointTest {

    /** 测试用接口：显式指定 message 的检查点方法 */
    interface CheckpointAgent {
        @HumanCheckpoint(message = "确认执行研究步骤？")
        String research(String task);
    }

    /** 测试用接口：使用默认 message 的检查点方法 */
    interface DefaultCheckpointAgent {
        @HumanCheckpoint
        String run(String task);
    }

    @Test
    void annotation_shouldBeApplicableToMethod() {
        Method method = findMethod(CheckpointAgent.class.getMethods(), "research");
        assertNotNull(method, "方法 research 应存在");
        assertTrue(method.isAnnotationPresent(HumanCheckpoint.class),
                "research 方法应标注 @HumanCheckpoint");
    }

    @Test
    void annotation_shouldBeReadableAtRuntime() {
        Method method = findMethod(CheckpointAgent.class.getMethods(), "research");
        assertNotNull(method);
        HumanCheckpoint annotation = method.getAnnotation(HumanCheckpoint.class);
        assertNotNull(annotation, "运行时反射应能读取 @HumanCheckpoint（@Retention(RUNTIME)）");
        assertEquals("确认执行研究步骤？", annotation.message(), "显式 message 应正确读取");
    }

    @Test
    void annotation_shouldUseDefaultMessageWhenNotSpecified() {
        Method method = findMethod(DefaultCheckpointAgent.class.getMethods(), "run");
        assertNotNull(method);
        HumanCheckpoint annotation = method.getAnnotation(HumanCheckpoint.class);
        assertNotNull(annotation);
        assertEquals("确认执行此步骤？", annotation.message(), "未指定 message 时应使用默认值");
    }

    private Method findMethod(Method[] methods, String name) {
        for (Method method : methods) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        return null;
    }
}
