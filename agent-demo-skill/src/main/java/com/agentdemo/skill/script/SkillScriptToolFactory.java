package com.agentdemo.skill.script;

import com.agentdemo.skill.entity.ScriptParam;
import com.agentdemo.skill.entity.SkillDefinition;
import com.agentdemo.skill.entity.SkillScript;
import dev.langchain4j.agent.tool.Tool;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 脚本工具动态生成工厂（CR-001 Task-33，AC-T02）
 * <p>
 * 业务含义：为 Skill 自带脚本动态生成带 @Tool 注解的工具类（知识库动态工具先例）。
 * 方法名 skill_{skillId}_{scriptName}，参数按脚本声明 schema 动态生成（string→String、
 * integer→Long、number→Double、boolean→Boolean），方法体委托 SkillScriptExecutor 执行。
 * </p>
 * <p>
 * 设计要点：生成的工具对象由 SkillScriptToolRegistrar 缓存并直接注入会话工具集，
 * 不经 ToolRegistry 登记权限——脚本工具天然不进入系统权限模型（决策 9，AC-T03）。
 * </p>
 */
public final class SkillScriptToolFactory {

    private static final Logger log = LoggerFactory.getLogger(SkillScriptToolFactory.class);

    private SkillScriptToolFactory() {
    }

    /**
     * 为脚本生成工具对象
     *
     * @param skill   所属技能
     * @param script  脚本声明
     * @param executor 脚本执行器
     * @return 带 @Tool 注解的工具实例
     */
    public static Object createTool(SkillDefinition skill, SkillScript script, SkillScriptExecutor executor) {
        String methodName = SkillScriptToolRegistrar.buildToolName(skill.getId(), script.getName());
        String className = "com.agentdemo.skill.tool.generated.SkillScriptTool_" + Math.abs(methodName.hashCode());
        String description = buildDescription(skill, script);

        try {
            List<ScriptParam> declaredParams = script.getParams() == null ? List.of() : script.getParams();

            DynamicType.Builder.MethodDefinition.ParameterDefinition.Initial<?> initial = new ByteBuddy()
                    .subclass(Object.class)
                    .name(className)
                    .defineMethod(methodName, String.class, Modifier.PUBLIC);
            DynamicType.Builder.MethodDefinition.ParameterDefinition<?> builder = initial;
            for (ScriptParam p : declaredParams) {
                builder = builder.withParameter(mapType(p.getType()), p.getName());
            }

            Class<?> toolClass = builder
                    .intercept(MethodDelegation.to(new ScriptToolInterceptor(script, executor)))
                    .annotateMethod(AnnotationDescription.Builder.ofType(Tool.class)
                            .defineArray("value", description)
                            .build())
                    .annotateType(AnnotationDescription.Builder
                            .ofType(com.agentdemo.tools.permission.DefaultToolPermission.class)
                            .define("value", com.agentdemo.tools.permission.ToolPermissionLevel.ALLOW)
                            .build())
                    .make()
                    .load(SkillScriptToolFactory.class.getClassLoader())
                    .getLoaded();

            Object tool = toolClass.getDeclaredConstructor().newInstance();
            log.info("生成脚本工具: method={}, skill={}, script={}", methodName, skill.getId(), script.getName());
            return tool;
        } catch (Exception e) {
            throw new RuntimeException("生成脚本工具失败, skill=" + skill.getId() + ", script=" + script.getName(), e);
        }
    }

    /**
     * 构建工具描述（含参数说明，供 LLM 路由与传参）
     */
    private static String buildDescription(SkillDefinition skill, SkillScript script) {
        StringBuilder sb = new StringBuilder();
        if (script.getDescription() != null && !script.getDescription().isBlank()) {
            sb.append(script.getDescription());
        } else {
            sb.append("技能「").append(skill.getName()).append("」自带脚本工具");
        }
        sb.append("。适用场景：需要该技能自带能力时调用。");
        if (script.getParams() != null && !script.getParams().isEmpty()) {
            List<String> paramDesc = new ArrayList<>();
            for (ScriptParam p : script.getParams()) {
                paramDesc.add(p.getName() + "（" + (p.isRequired() ? "必填" : "可选")
                        + (p.getDescription() != null && !p.getDescription().isBlank() ? "，" + p.getDescription() : "") + "）");
            }
            sb.append("参数：").append(String.join("；", paramDesc)).append("。");
        }
        sb.append("返回脚本执行结果。");
        return sb.toString();
    }

    private static Class<?> mapType(String type) {
        if (type == null) {
            return String.class;
        }
        switch (type.trim().toLowerCase()) {
            case "integer" -> {
                return Long.class;
            }
            case "number" -> {
                return Double.class;
            }
            case "boolean" -> {
                return Boolean.class;
            }
            default -> {
                return String.class;
            }
        }
    }

    /**
     * ByteBuddy 方法拦截器：按参数声明顺序绑定参数名，委托脚本执行器
     */
    public static class ScriptToolInterceptor {

        private final SkillScript script;
        private final SkillScriptExecutor executor;

        public ScriptToolInterceptor(SkillScript script, SkillScriptExecutor executor) {
            this.script = script;
            this.executor = executor;
        }

        @RuntimeType
        public String invoke(@AllArguments Object[] args) {
            Map<String, Object> params = new LinkedHashMap<>();
            List<ScriptParam> declared = script.getParams() == null ? List.of() : script.getParams();
            for (int i = 0; i < declared.size() && i < args.length; i++) {
                params.put(declared.get(i).getName(), args[i]);
            }
            return executor.execute(script, params).message();
        }
    }
}
