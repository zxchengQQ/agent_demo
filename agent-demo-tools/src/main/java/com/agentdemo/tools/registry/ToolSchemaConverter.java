package com.agentdemo.tools.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;

/**
 * 工具 Schema 转换器
 * <p>
 * 业务含义：将 {@link ToolRegistry} 中注册的 @Tool 注解方法转换为 OpenAI 兼容的
 * tools JSON Schema 字符串，供 LLM 进行 function calling 时识别可用工具及其参数定义。
 * </p>
 * <p>
 * 调用方：agent 层构建 LLM 请求时，调用 convertToJson 获取工具描述
 * </p>
 */
@Component
public class ToolSchemaConverter {

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ToolSchemaConverter(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * 将所有注册工具的 @Tool 方法转换为 OpenAI 兼容的 tools JSON Schema 字符串
     * <p>
     * 业务含义：遍历 ToolRegistry 中已注册的工具 Bean，反射扫描 @Tool 注解方法，
     * 生成 JSON 数组，每个元素描述一个可调用的工具函数。
     * </p>
     *
     * @return OpenAI 兼容的 tools JSON 数组字符串
     */
    public String convertToJson() {
        return convertToJson(toolRegistry.listTools());
    }

    /**
     * 将指定工具列表的 @Tool 方法转换为 OpenAI 兼容的 tools JSON Schema 字符串
     * <p>
     * 业务含义：工具按需加载时，仅对当前 Agent 绑定的工具生成 Schema，
     * 避免把未指定的可选工具（如 MCP/知识库工具）暴露给 LLM。
     * </p>
     *
     * @param tools 工具对象列表
     * @return OpenAI 兼容的 tools JSON 数组字符串
     */
    public String convertToJson(List<Object> tools) {
        ArrayNode toolsArray = objectMapper.createArrayNode();

        for (Object tool : tools) {
            // 使用 getDeclaredMethods 扫描类自身声明的方法（不含继承方法）
            for (Method method : tool.getClass().getDeclaredMethods()) {
                if (method.isAnnotationPresent(Tool.class)) {
                    toolsArray.add(buildToolSchema(method));
                }
            }
        }

        return toolsArray.toString();
    }

    /**
     * 将所有注册工具的 @Tool 方法转换为人类可读的工具描述文本
     * <p>
     * 业务含义：动态生成工具描述注入系统提示词，替代硬编码的工具描述（CR-001）。
     * 遍历 ToolRegistry 中已注册的工具 Bean，反射扫描 @Tool 注解方法，
     * 生成格式为 "- {方法名}: {描述}" 的工具列表文本。
     * </p>
     *
     * @return 人类可读的工具描述文本字符串
     */
    public String convertToDescriptionText() {
        return convertToDescriptionText(toolRegistry.listTools());
    }

    /**
     * 将指定工具列表的 @Tool 方法转换为人类可读的工具描述文本
     * <p>
     * 业务含义：工具按需加载时，仅对当前 Agent 绑定的工具生成描述文本，
     * 注入系统提示词的工具清单与 LLM 实际可调用工具保持一致。
     * </p>
     *
     * @param tools 工具对象列表
     * @return 人类可读的工具描述文本字符串
     */
    public String convertToDescriptionText(List<Object> tools) {
        StringBuilder sb = new StringBuilder();
        sb.append("你可以调用以下工具来辅助回答：\n");
        for (Object tool : tools) {
            for (Method method : tool.getClass().getDeclaredMethods()) {
                if (method.isAnnotationPresent(Tool.class)) {
                    Tool toolAnnotation = method.getAnnotation(Tool.class);
                    String description = String.join(" ", toolAnnotation.value());
                    sb.append("- ").append(method.getName())
                            .append(": ").append(description).append("\n");
                }
            }
        }
        return sb.toString();
    }

    /**
     * 构建单个工具方法的 JSON Schema
     * <p>
     * 业务含义：提取方法名、@Tool 描述、参数信息，按 OpenAI function calling 协议构建
     * {"type":"function","function":{"name":"...","description":"...","parameters":{...}}}
     * </p>
     *
     * @param method 标注了 @Tool 的方法
     * @return 单个工具的 JSON Schema 对象
     */
    private ObjectNode buildToolSchema(Method method) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "function");

        ObjectNode function = objectMapper.createObjectNode();
        function.put("name", method.getName());

        // 提取 @Tool 注解的描述值（value 返回 String[]，合并为单个字符串）
        Tool toolAnnotation = method.getAnnotation(Tool.class);
        function.put("description", String.join(" ", toolAnnotation.value()));

        // 构建 parameters JSON Schema
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();
        ArrayNode required = objectMapper.createArrayNode();

        // 遍历方法参数，构建 properties 和 required
        for (Parameter param : method.getParameters()) {
            String paramName = param.getName();
            String jsonType = mapJavaTypeToJsonType(param.getType());

            ObjectNode paramSchema = objectMapper.createObjectNode();
            paramSchema.put("type", jsonType);
            properties.set(paramName, paramSchema);

            // 所有参数都标记为必填
            required.add(paramName);
        }

        parameters.set("properties", properties);
        parameters.set("required", required);

        function.set("parameters", parameters);
        schema.set("function", function);

        return schema;
    }

    /**
     * Java 类型到 JSON Schema 类型的映射
     * <p>
     * 业务含义：LLM 需要正确的参数类型信息才能生成合法的参数值
     * </p>
     *
     * @param type Java 参数类型
     * @return JSON Schema 类型字符串
     */
    private String mapJavaTypeToJsonType(Class<?> type) {
        if (type == String.class) {
            return "string";
        }
        if (type == int.class || type == Integer.class) {
            return "integer";
        }
        if (type == double.class || type == Double.class) {
            return "number";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        }
        if (type.isArray()) {
            return "array";
        }
        if (type == List.class || type == java.util.Collection.class) {
            return "array";
        }
        return "string";
    }
}
