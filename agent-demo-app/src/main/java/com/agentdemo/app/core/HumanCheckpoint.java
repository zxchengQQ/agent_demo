package com.agentdemo.app.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 模板预设检查点注解（工作流 HITL）
 * <p>
 * 业务含义：标注在 {@code @Agent} 接口方法上，声明该方法执行前需人工确认。
 * AgentExecutor 在执行前通过反射检测此注解，存在则暂停工作流为 WAITING_USER，
 * 推送确认型 askUser 数据等待用户确认（AC-N02/AC-T02）。
 * </p>
 * <p>
 * 用法示例：
 * <pre>{@code
 * @HumanCheckpoint(message = "确认执行分析步骤？")
 * String analyze(@UserMessage String input);
 * }</pre>
 * </p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface HumanCheckpoint {

    /** 检查点确认提示语（默认值，AgentExecutor 构造确认卡片时使用） */
    String message() default "确认执行此步骤？";
}
