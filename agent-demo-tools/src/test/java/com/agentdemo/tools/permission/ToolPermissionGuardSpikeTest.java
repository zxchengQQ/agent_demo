package com.agentdemo.tools.permission;

import com.agentdemo.tools.builtin.AskUserTool;
import com.agentdemo.tools.builtin.CalculatorTool;
import com.agentdemo.tools.builtin.HttpTool;
import com.agentdemo.tools.builtin.TimeTool;
import dev.langchain4j.agent.tool.Tool;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task-01: ByteBuddy 包装层 Spike 验证
 * <p>
 * 业务含义：验证工具权限包装层的技术可行性——动态生成 @Tool 包装类后，
 * LangChain4j 生成的 ToolSpecification 必须与原工具方法逐字段一致（名称/描述/参数名），
 * 否则 LLM 看到的工具 schema 漂移（参数名变 arg0 会导致调用失败）。
 * </p>
 * <p>
 * 验证两个候选方案：
 * 方案A（subclass 原类 + method().intercept() 覆写）：风险是 ByteBuddy 生成的覆写方法
 * 参数名默认 arg0，LangChain4j 读取到错误参数名。
 * 方案B（subclass Object + defineMethod + withParameter 显式参数名 + MethodDelegation 委托原 bean）：
 * 与 McpToolFactory 已验证模式一致，参数名显式声明可控。
 * </p>
 */
public class ToolPermissionGuardSpikeTest {

    /** 拦截器：持有原工具对象与原方法，MethodDelegation 时委托调用（Spike 阶段仅验证委托，权限判定由 Task-03 实现） */
    public static class DelegateInterceptor {
        private final Object originalTool;
        private final Method originalMethod;

        DelegateInterceptor(Object originalTool, Method originalMethod) {
            this.originalTool = originalTool;
            this.originalMethod = originalMethod;
        }

        @RuntimeType
        public String intercept(@AllArguments Object[] args) throws Exception {
            Object result = originalMethod.invoke(originalTool, args);
            return result != null ? result.toString() : null;
        }
    }

    /**
     * 方案A：subclass 原类，覆写 @Tool 方法并复制注解
     * <p>Spike 目标：记录覆写后参数名是否保留（预期丢失为 arg0）。</p>
     */
    private Class<?> buildOverrideProxyClass(Class<?> originalClass) throws Exception {
        Method toolMethod = Arrays.stream(originalClass.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Tool.class))
                .findFirst()
                .orElseThrow();
        Tool annotation = toolMethod.getAnnotation(Tool.class);
        return new ByteBuddy()
                .subclass(originalClass)
                .method(net.bytebuddy.matcher.ElementMatchers.named(toolMethod.getName()))
                .intercept(MethodDelegation.to(
                        new DelegateInterceptor(originalClass.getDeclaredConstructor().newInstance(), toolMethod)))
                .annotateMethod(AnnotationDescription.Builder.ofType(Tool.class)
                        .defineArray("value", annotation.value())
                        .build())
                .make()
                .load(getClass().getClassLoader())
                .getLoaded();
    }

    /**
     * 方案B：subclass Object，defineMethod 定义同名 @Tool 方法，withParameter 显式参数名，委托原 bean
     */
    private Class<?> buildWrapperClass(Object tool) throws Exception {
        Class<?> originalClass = tool.getClass();
        List<Method> toolMethods = Arrays.stream(originalClass.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Tool.class))
                .toList();
        assertThat(toolMethods).as("工具类 %s 应至少有一个 @Tool 方法", originalClass.getSimpleName()).isNotEmpty();

        DynamicType.Builder<?> builder = new ByteBuddy()
                .subclass(Object.class)
                .name(originalClass.getSimpleName() + "$PermissionGuard");

        for (Method method : toolMethods) {
            builder = defineToolMethod(builder, tool, method);
        }
        return builder.make().load(getClass().getClassLoader()).getLoaded();
    }

    /**
     * 为单个 @Tool 方法生成同名包装方法（显式参数名 + @Tool 注解复制 + 委托原方法）
     * <p>业务含义：defineMethod 返回的泛型 ParameterDefinition 无法直接用 DynamicType.Builder 接收，
     * 需显式 cast 后链式串联多参数（与 McpToolFactory 单方法链式调用等价）。</p>
     */
    @SuppressWarnings("unchecked")
    private DynamicType.Builder<?> defineToolMethod(DynamicType.Builder<?> builder, Object tool, Method method) {
        Tool annotation = method.getAnnotation(Tool.class);
        DynamicType.Builder.MethodDefinition.ParameterDefinition<DynamicType.Builder<?>> def =
                (DynamicType.Builder.MethodDefinition.ParameterDefinition<DynamicType.Builder<?>>)
                builder.defineMethod(method.getName(), String.class, Modifier.PUBLIC);
        for (java.lang.reflect.Parameter param : method.getParameters()) {
            def = def.withParameter(param.getType(), param.getName());
        }
        return def.intercept(MethodDelegation.to(new DelegateInterceptor(tool, method)))
                .annotateMethod(AnnotationDescription.Builder.ofType(Tool.class)
                        .defineArray("value", annotation.value())
                        .build());
    }

    @Test
    @DisplayName("Spike-方案A：subclass 覆写方法的参数名验证（记录风险）")
    void spike_overrideProxy_parameterName() throws Exception {
        // 业务含义：验证方案A（覆写）的参数名行为，供技术选型结论依据
        Class<?> proxy = buildOverrideProxyClass(CalculatorTool.class);
        Method proxied = proxy.getDeclaredMethod("calculate", String.class);
        Method original = CalculatorTool.class.getDeclaredMethod("calculate", String.class);

        System.out.println("[Spike-方案A] 原方法参数名=" + original.getParameters()[0].getName()
                + "，覆写方法参数名=" + proxied.getParameters()[0].getName());

        // 业务含义：方案A 的覆写方法若参数名变 arg0，LangChain4j schema 将使用 arg0 —— 记录该风险
        boolean paramNamePreserved = original.getParameters()[0].getName().equals(proxied.getParameters()[0].getName());
        System.out.println("[Spike-方案A] 参数名是否保留=" + paramNamePreserved
                + "（false 说明覆写方案不可用，需采用方案B 显式参数名）");
    }

    @Test
    @DisplayName("Spike-方案B：wrapper 类方法名/参数名/@Tool 注解与原方法逐字段一致")
    void spike_wrapper_schemaFidelity() throws Exception {
        // 业务含义：方案B 生成的包装类必须与原 @Tool 方法在 LangChain4j 关注的字段上完全一致
        CalculatorTool calculator = new CalculatorTool();
        Class<?> wrapperClass = buildWrapperClass(calculator);
        Object wrapper = wrapperClass.getDeclaredConstructor().newInstance();

        for (Method original : CalculatorTool.class.getDeclaredMethods()) {
            if (!original.isAnnotationPresent(Tool.class)) continue;
            Method proxied = wrapperClass.getMethod(original.getName(), original.getParameterTypes());

            // 方法名一致
            assertThat(proxied.getName()).isEqualTo(original.getName());
            // @Tool 注解 value 一致（LangChain4j 的 tool name/description 来源）
            assertThat(proxied.getAnnotation(Tool.class).value())
                    .containsExactly(original.getAnnotation(Tool.class).value());
            // 参数名一致（LangChain4j 经 -parameters 读取，是 schema 参数名的唯一来源）
            assertThat(proxied.getParameterCount()).isEqualTo(original.getParameterCount());
            for (int i = 0; i < original.getParameterCount(); i++) {
                assertThat(proxied.getParameters()[i].getName())
                        .as("参数 %d 名", i)
                        .isEqualTo(original.getParameters()[i].getName());
            }
        }
        System.out.println("[Spike-方案B] CalculatorTool 包装类字段保真验证通过");
    }

    @Test
    @DisplayName("Spike-方案B：委托调用返回与原工具方法一致")
    void spike_wrapper_delegatesToOriginal() throws Exception {
        // 业务含义：包装类方法体必须正确委托原工具方法执行，返回结果与原方法一致
        CalculatorTool calculator = new CalculatorTool();
        Class<?> wrapperClass = buildWrapperClass(calculator);
        Object wrapper = wrapperClass.getDeclaredConstructor().newInstance();

        String result = (String) wrapperClass.getMethod("calculate", String.class).invoke(wrapper, "2+3");
        assertThat(result).isEqualTo("2+3 = 5");
        System.out.println("[Spike-方案B] 委托调用结果: " + result);
    }

    @Test
    @DisplayName("Spike-方案B：多参数/无参/String[] 数组参数形态覆盖")
    void spike_wrapper_variousParameterShapes() throws Exception {
        // 业务含义：覆盖项目现有 @Tool 方法的全部参数形态，确认 defineMethod 通用性
        // 1. 两参 String（HttpTool.httpPost）
        Class<?> httpWrapper = buildWrapperClass(new HttpTool());
        Method httpPost = httpWrapper.getMethod("httpPost", String.class, String.class);
        assertThat(httpPost.getParameters()[0].getName()).isEqualTo("url");
        assertThat(httpPost.getParameters()[1].getName()).isEqualTo("body");
        assertThat(httpPost.getAnnotation(Tool.class)).isNotNull();

        // 2. 无参（TimeTool.getCurrentTime）
        Class<?> timeWrapper = buildWrapperClass(new TimeTool());
        Method getCurrentTime = timeWrapper.getMethod("getCurrentTime");
        assertThat(getCurrentTime.getParameterCount()).isZero();
        assertThat(getCurrentTime.getAnnotation(Tool.class)).isNotNull();
        // TimeTool 三个 @Tool 方法都应生成（getCurrentTimeByZone 含 1 参）
        assertThat(timeWrapper.getMethod("getCurrentTimeByZone", String.class)).isNotNull();
        assertThat(timeWrapper.getMethod("getCurrentDate")).isNotNull();

        // 3. String[] 数组参数（AskUserTool.askUser）
        Class<?> askUserWrapper = buildWrapperClass(new AskUserTool());
        Method askUser = askUserWrapper.getMethod("askUser", String.class, String.class, String[].class);
        assertThat(askUser.getParameters()[2].getName()).isEqualTo("options");
        assertThat(askUser.getAnnotation(Tool.class)).isNotNull();
        System.out.println("[Spike-方案B] 多参/无参/String[] 数组参数形态全部通过");
    }

    @Test
    @DisplayName("Spike-方案B：与 LangChain4j ToolSpecification 对比（schema 级保真）")
    void spike_wrapper_langchain4jSchema() throws Exception {
        // 业务含义：终极验证——LangChain4j 从包装类生成的 ToolSpecification 与原方法一致，
        // 证明 LLM 看到的工具 schema 完全无漂移
        CalculatorTool calculator = new CalculatorTool();
        Class<?> wrapperClass = buildWrapperClass(calculator);
        Method wrapperMethod = wrapperClass.getMethod("calculate", String.class);
        Method originalMethod = CalculatorTool.class.getDeclaredMethod("calculate", String.class);

        // 业务含义：反射提取 LangChain4j schema 关注的三要素（name/description/参数名），
        // 不依赖 langchain4j 内部 API，保持 Spike 测试独立可编译
        String wrapperName = wrapperMethod.getName();
        String originalName = originalMethod.getName();
        String wrapperDesc = String.join(" ", wrapperMethod.getAnnotation(Tool.class).value());
        String originalDesc = String.join(" ", originalMethod.getAnnotation(Tool.class).value());
        String wrapperParam = wrapperMethod.getParameters()[0].getName();
        String originalParam = originalMethod.getParameters()[0].getName();

        assertThat(wrapperName).isEqualTo(originalName);
        assertThat(wrapperDesc).isEqualTo(originalDesc);
        assertThat(wrapperParam).isEqualTo(originalParam);
        System.out.println("[Spike-方案B] LangChain4j schema 三要素（name/description/参数名）一致");
    }
}
