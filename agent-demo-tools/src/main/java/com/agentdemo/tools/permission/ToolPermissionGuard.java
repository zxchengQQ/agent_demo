package com.agentdemo.tools.permission;

import com.agentdemo.tools.registry.ToolIdResolver;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.FieldValue;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具权限包装层（执行期 deny 零触发防线）
 * <p>
 * 业务含义：为所有经 ToolRegistry 出口分发的工具生成"贴身保镖"——ByteBuddy 动态生成的
 * 包装类。LangChain4j 反射直调路径命中包装层拦截器，按包装时捕获的权限等级裁决：
 * DENY → 方法体零触发返回固定拒绝文案（AC-T03 第二道防线）；ALLOW → 委托原工具方法；
 * ASK → 防御性拒绝（理论不可达，加载期已按调用方能力排除，见技术方案 §3.2）。
 * </p>
 * <p>
 * 生成机制（Spike 结论·方案 B）：subclass Object + defineMethod 同名 @Tool 方法 +
 * withParameter 显式参数名 + MethodDelegation 委托拦截器，与 McpToolFactory 已验证模式一致，
 * 保证 LangChain4j 生成的 ToolSpecification 与原方法逐字段一致（方法名/描述/参数名）。
 * </p>
 * <p>
 * 缓存设计：
 * 1. 代理类 Class 按原工具类缓存（ByteBuddy 生成昂贵，每类仅 1 次）；
 * 2. 包装实例按 (工具对象, 权限版本) 缓存——同版本复用（本轮工具列表稳定，AC-M01 不中途突变），
 * 权限变更版本递增，下一轮解析生成新实例反映新权限。
 * </p>
 * <p>
 * 降级：生成失败返回原工具对象并记 ERROR（加载期过滤仍生效，仅放弃第二道防线）；
 * 权限功能关闭（enabled=false）时权限服务返回 ALLOW，包装直通委托。
 * </p>
 */
@Slf4j
@Component
public class ToolPermissionGuard {

    /** 拒绝文案：deny 工具被执行期拦截时返回（脱敏，不含配置细节，AC-S04） */
    public static final String DENY_MESSAGE = "该工具已被禁止调用，无法执行。请告知用户无法完成此操作，或调整方案。";

    /** 防御文案：ask 工具理论上不可达直调路径（加载期已排除），仅防御未来异常路径 */
    private static final String ASK_DEFENSE_MESSAGE = "该工具需要用户确认后方可调用，但当前执行路径未提供确认通道。";

    /** 代理类字段名：绑定原工具对象引用 */
    private static final String FIELD_ORIGINAL = "__originalTool";

    /** 代理类字段名：绑定包装时捕获的各方法权限等级（AC-M01 解析时快照） */
    private static final String FIELD_LEVELS = "__capturedLevels";

    private final ToolPermissionService toolPermissionService;
    private final ToolIdResolver toolIdResolver;

    /** 代理类缓存：原工具类 → ByteBuddy 包装类 */
    private final Map<Class<?>, Class<?>> proxyClassCache = new ConcurrentHashMap<>();

    /** 包装实例缓存：原工具对象 → (权限版本 → 包装实例) */
    private final Map<Object, Map<Long, Object>> wrapperCache = new ConcurrentHashMap<>();

    public ToolPermissionGuard(ToolPermissionService toolPermissionService, ToolIdResolver toolIdResolver) {
        this.toolPermissionService = toolPermissionService;
        this.toolIdResolver = toolIdResolver;
    }

    /**
     * 为工具对象生成/复用权限包装对象
     * <p>
     * 业务含义：ToolRegistry 出口统一调用。无 @Tool 方法的对象原样返回（无需包装）。
     * 包装实例按 (工具对象, 权限版本) 缓存；权限版本取自 ToolPermissionService（显式配置变更递增）。
     * </p>
     *
     * @param tool 已通过加载期过滤的工具对象
     * @return 包装对象（或原对象：无 @Tool 方法 / 生成失败降级）
     */
    public Object wrap(Object tool) {
        if (tool == null || findToolMethods(tool.getClass()).isEmpty()) {
            return tool;
        }
        // 业务含义：同版本复用包装实例（本轮稳定）；权限变更版本递增 → 下一次 wrap 生成新实例（AC-M01 下轮生效）
        long version = toolPermissionService.getVersion();
        return wrapperCache
                .computeIfAbsent(tool, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(version, v -> doWrap(tool));
    }

    /**
     * 生成包装实例：复用代理类 + 绑定原对象引用与捕获的权限等级
     */
    private Object doWrap(Object tool) {
        try {
            Class<?> wrapperClass = proxyClassCache.computeIfAbsent(tool.getClass(), this::buildWrapperClass);
            Object wrapper = wrapperClass.getDeclaredConstructor().newInstance();
            wrapperClass.getField(FIELD_ORIGINAL).set(wrapper, tool);
            wrapperClass.getField(FIELD_LEVELS).set(wrapper, captureLevels(tool));
            return wrapper;
        } catch (Exception e) {
            log.error("工具包装层生成失败，降级返回原工具对象（加载期过滤仍生效）: {}",
                    tool.getClass().getSimpleName(), e);
            return tool;
        }
    }

    /**
     * 构建包装代理类：subclass Object + 逐 @Tool 方法生成同名方法（Spike 方案 B）
     * <p>
     * 业务含义：代理类含两个公共字段（原对象引用 / 捕获权限等级），拦截器经 @FieldValue 读取；
     * 拦截器本身无状态可跨实例复用，代理类可安全按原类缓存。
     * </p>
     */
    private Class<?> buildWrapperClass(Class<?> originalClass) {
        DynamicType.Builder<?> builder = new ByteBuddy()
                .subclass(Object.class)
                .name(originalClass.getSimpleName() + "$PermissionGuard")
                .defineField(FIELD_ORIGINAL, Object.class, Modifier.PUBLIC)
                .defineField(FIELD_LEVELS, Map.class, Modifier.PUBLIC);

        for (Method method : findToolMethods(originalClass)) {
            builder = defineGuardedMethod(builder, method);
        }
        return builder.make().load(originalClass.getClassLoader()).getLoaded();
    }

    /**
     * 为单个 @Tool 方法生成同名包装方法：显式参数名 + @Tool 注解复制 + MethodDelegation 委托拦截器
     */
    @SuppressWarnings("unchecked")
    private DynamicType.Builder<?> defineGuardedMethod(DynamicType.Builder<?> builder, Method method) {
        Tool annotation = method.getAnnotation(Tool.class);
        DynamicType.Builder.MethodDefinition.ParameterDefinition<DynamicType.Builder<?>> def =
                (DynamicType.Builder.MethodDefinition.ParameterDefinition<DynamicType.Builder<?>>)
                builder.defineMethod(method.getName(), String.class, Modifier.PUBLIC);
        for (java.lang.reflect.Parameter param : method.getParameters()) {
            def = def.withParameter(param.getType(), param.getName());
        }
        return def.intercept(MethodDelegation.to(new PermissionGuardInterceptor()))
                .annotateMethod(AnnotationDescription.Builder.ofType(Tool.class)
                        .defineArray("value", annotation.value())
                        .build());
    }

    /**
     * 捕获工具各 @Tool 方法的当前权限等级（解析时快照，AC-M01）
     * <p>业务含义：权限等级经 toolId（category:name）查询权限服务；askUser 由权限服务豁免恒 ALLOW。</p>
     */
    private Map<String, ToolPermissionLevel> captureLevels(Object tool) {
        Map<String, ToolPermissionLevel> levels = new HashMap<>();
        for (Method method : findToolMethods(tool.getClass())) {
            String category = toolIdResolver.inferCategory(method.getName());
            String toolId = toolIdResolver.buildToolId(category, method.getName());
            levels.put(method.getName(), toolPermissionService.getPermission(toolId));
        }
        return levels;
    }

    /** 获取类中所有标注了 @Tool 的方法 */
    private List<Method> findToolMethods(Class<?> clazz) {
        java.util.ArrayList<Method> toolMethods = new java.util.ArrayList<>();
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Tool.class)) {
                toolMethods.add(method);
            }
        }
        return toolMethods;
    }

    /**
     * 包装层拦截器（无状态，经 @FieldValue 读取当前实例的原对象与捕获权限）
     * <p>
     * 业务含义：@This/@AllArguments/@Origin/@FieldValue 由 ByteBuddy MethodDelegation 注入，
     * 无需构造绑定，因此代理类可按原类安全缓存、拦截器单例复用。
     * </p>
     */
    public static class PermissionGuardInterceptor {

        @RuntimeType
        public String intercept(@AllArguments Object[] args,
                                @Origin Method method,
                                @FieldValue(FIELD_ORIGINAL) Object originalTool,
                                @FieldValue(FIELD_LEVELS) Map<String, ToolPermissionLevel> capturedLevels) throws Exception {
            String methodName = method.getName();
            ToolPermissionLevel level = capturedLevels != null ? capturedLevels.get(methodName) : null;

            if (level == ToolPermissionLevel.DENY) {
                // 纵深防御第二道防线：加载期已剔除但被注入时零触发（AC-T03）
                log.warn("工具权限 deny 兜底拦截: toolName={}, 调用来源=包装层拦截器", methodName);
                return DENY_MESSAGE;
            }
            if (level == ToolPermissionLevel.ASK) {
                // 理论不可达：ask 工具在加载期已按调用方能力排除，仅防御未来异常路径
                log.warn("工具权限 ask 防御拦截（理论不可达直调路径）: toolName={}, 调用来源=包装层拦截器", methodName);
                return ASK_DEFENSE_MESSAGE;
            }

            // ALLOW（或未知工具保守放行）：委托原工具方法执行，结果透传
            Method originalMethod = originalTool.getClass().getMethod(methodName, method.getParameterTypes());
            Object result = originalMethod.invoke(originalTool, args);
            return result != null ? result.toString() : null;
        }
    }
}
