package com.agentdemo.tools.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;

/**
 * 工具执行器
 * <p>
 * 业务含义：ReAct 循环中 LLM 返回 tool_calls 时，通过此方法执行对应工具。
 * 接收工具名和参数 JSON，从 {@link ToolRegistry} 查找对应 @Tool 方法，解析参数 JSON，反射调用，返回结果字符串。
 * </p>
 * <p>
 * 异常处理原则（AC-012）：工具执行失败时返回错误信息字符串，不抛出异常，
 * 保证 ReAct 循环不会被中断，LLM 可以根据错误信息决定下一步动作（如重试或换工具）。
 * </p>
 */
@Component
public class ToolExecutor {

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ToolExecutor(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * 执行工具调用
     * 业务含义：ReAct 循环中 LLM 返回 tool_calls 时，通过此方法执行对应工具
     * 异常处理：工具执行失败时返回错误信息字符串，不抛出异常（AC-012）
     *
     * @param toolName      工具名（对应 @Tool 注解方法的方法名）
     * @param argumentsJson 参数 JSON 字符串（LLM 生成）
     * @return 工具执行结果字符串；工具不存在或执行失败时返回对应的错误信息
     */
    public String execute(String toolName, String argumentsJson) {
        try {
            // 1. 查找工具方法
            MethodAndBean target = findToolMethod(toolName);
            if (target == null) {
                return "工具不存在: " + toolName + "。可用工具: " + listAvailableToolNames();
            }

            // 2. 解析参数 JSON
            JsonNode argsNode = objectMapper.readTree(argumentsJson);
            Object[] args = buildMethodArguments(target.method, argsNode);

            // 3. 反射调用
            Object result = target.method.invoke(target.bean, args);
            return result != null ? result.toString() : "null";
        } catch (Exception e) {
            // 工具执行失败：返回错误信息，不抛出异常
            // 反射调用抛出 InvocationTargetException 时，真实异常在 getCause() 中
            String errorMsg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            return "工具执行失败: " + errorMsg;
        }
    }

    /**
     * 查找指定名称的 @Tool 方法
     * 业务含义：遍历所有已注册工具 Bean，按方法名匹配带 @Tool 注解的方法
     *
     * @param toolName 工具名（方法名）
     * @return 方法与 Bean 的封装，未找到返回 null
     */
    private MethodAndBean findToolMethod(String toolName) {
        for (Object bean : toolRegistry.listTools()) {
            for (Method method : bean.getClass().getDeclaredMethods()) {
                if (method.isAnnotationPresent(Tool.class)
                        && method.getName().equals(toolName)) {
                    return new MethodAndBean(bean, method);
                }
            }
        }
        return null;
    }

    /**
     * 列出所有已注册工具的方法名
     * 业务含义：工具不存在时告知 LLM 可用工具列表，帮助其自我纠正（BUG 修复）
     *
     * @return 可用工具名列表，逗号分隔
     */
    private String listAvailableToolNames() {
        List<String> names = new ArrayList<>();
        for (Object bean : toolRegistry.listTools()) {
            for (Method method : bean.getClass().getDeclaredMethods()) {
                if (method.isAnnotationPresent(Tool.class)) {
                    names.add(method.getName());
                }
            }
        }
        return String.join(", ", names);
    }

    /**
     * 根据方法参数信息从 JSON 中构建调用参数数组
     * 业务含义：按参数名从 JSON 中提取值并转换为对应 Java 类型
     * 参数缺失时注入类型默认值（null/0/false），不抛出异常
     *
     * @param method  目标方法
     * @param argsNode 参数 JSON 节点
     * @return 参数值数组
     */
    private Object[] buildMethodArguments(Method method, JsonNode argsNode) {
        Parameter[] parameters = method.getParameters();
        Object[] args = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            Parameter param = parameters[i];
            String paramName = param.getName();
            JsonNode valueNode = argsNode.path(paramName);
            args[i] = convertValue(valueNode, param.getType());
        }
        return args;
    }

    /**
     * 将 JSON 节点转换为目标 Java 类型
     * 业务含义：支持 String/int/Integer/long/Long/boolean/Boolean 等常见类型转换
     * 缺失节点（MissingNode）时返回类型默认值
     *
     * @param valueNode    JSON 值节点
     * @param targetType 目标 Java 类型
     * @return 转换后的 Java 值
     */
    private Object convertValue(JsonNode valueNode, Class<?> targetType) {
        if (valueNode == null || valueNode.isMissingNode() || valueNode.isNull()) {
            return getDefaultValue(targetType);
        }
        if (targetType == String.class) {
            return valueNode.asText();
        }
        if (targetType == int.class || targetType == Integer.class) {
            return valueNode.asInt();
        }
        if (targetType == long.class || targetType == Long.class) {
            return valueNode.asLong();
        }
        if (targetType == boolean.class || targetType == Boolean.class) {
            return valueNode.asBoolean();
        }
        if (targetType == double.class || targetType == Double.class) {
            return valueNode.asDouble();
        }
        // 其他类型默认按字符串取值
        return valueNode.asText();
    }

    /**
     * 获取类型的默认值
     * 业务含义：参数 JSON 缺少字段时，为基本类型注入 JVM 默认值，引用类型注入 null
     *
     * @param targetType 目标类型
     * @return 默认值
     */
    private Object getDefaultValue(Class<?> targetType) {
        if (targetType == int.class) return 0;
        if (targetType == long.class) return 0L;
        if (targetType == boolean.class) return false;
        if (targetType == double.class) return 0.0;
        if (targetType == float.class) return 0.0f;
        return null;
    }

    /**
     * 方法与 Bean 的封装记录
     *
     * @param bean   工具 Bean 实例
     * @param method @Tool 注解方法
     */
    private record MethodAndBean(Object bean, Method method) {}
}
