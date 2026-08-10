package com.agentdemo.mcp.tool;

import com.agentdemo.mcp.entity.McpToolInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.implementation.MethodDelegation;
import org.springframework.stereotype.Component;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * MCP 动态 Tool 工厂
 * <p>
 * 业务含义：为每个 MCP Server 提供的工具动态生成独立的 @Tool 代理类，使 Agent 通过
 * Function Calling 直接调用 MCP 工具，与本地工具（CalculatorTool/TimeTool 等）一视同仁。
 * </p>
 * <p>
 * 生成规则：
 * 1. 类名：com.agentdemo.mcp.tool.McpTool_{sanitizedServerName}_{sanitizedToolName}
 * 2. 方法名：mcp_{sanitizedServerName}_{sanitizedToolName}（保证不同 Server 同名工具不冲突，AC-025）
 * 3. 方法参数：String argsJson（统一单参数，简化 ByteBuddy 生成逻辑）
 * 4. 方法带 @Tool 注解，描述包含 Server 名 + 工具原始描述 + 参数 JSON Schema
 * 5. 方法内部委托给 {@link McpToolInterceptor#execute(Object[])}，
 *    拦截器再委托给 {@link McpToolExecutor#execute(String, String, String)}
 * </p>
 * <p>
 * 技术选型：使用 ByteBuddy 而非 CGLIB，因为 LangChain4j 要求 Tool 方法上必须有 @Tool 注解，
 * CGLIB 生成的代理方法不会继承父类/接口的注解，而 ByteBuddy 可以在生成方法时直接写入注解。
 * 参考：CR-003 KnowledgeBaseToolFactory 已验证模式
 * </p>
 */
@Slf4j
@Component
public class McpToolFactory {

    private final McpToolExecutor toolExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public McpToolFactory(McpToolExecutor toolExecutor) {
        this.toolExecutor = toolExecutor;
    }

    /**
     * 为指定 Server 的工具批量生成动态 Tool 实例
     *
     * @param serverName MCP Server 名称
     * @param toolInfos  工具元数据列表
     * @return Tool 实例列表（与 toolInfos 顺序对应）
     */
    public List<Object> createTools(String serverName, List<McpToolInfo> toolInfos) {
        List<Object> tools = new ArrayList<>();
        if (toolInfos == null || toolInfos.isEmpty()) {
            return tools;
        }

        for (McpToolInfo toolInfo : toolInfos) {
            Object tool = createTool(serverName, toolInfo);
            tools.add(tool);
        }
        log.info("生成 MCP Tool 完成: server={}, 工具数={}", serverName, tools.size());
        return tools;
    }

    /**
     * 为单个工具生成动态 Tool 实例
     */
    private Object createTool(String serverName, McpToolInfo toolInfo) {
        String methodName = buildMethodName(serverName, toolInfo.getOriginalName());
        String className = buildClassName(serverName, toolInfo.getOriginalName());
        String description = buildToolDescription(serverName, toolInfo);

        // 同步 registeredName，便于后续注销时按方法名匹配
        toolInfo.setRegisteredName(methodName);

        try {
            Class<?> toolClass = new ByteBuddy()
                    .subclass(Object.class)
                    .name(className)
                    .defineMethod(methodName, String.class, Modifier.PUBLIC)
                    .withParameter(String.class, "argsJson")
                    .intercept(MethodDelegation.to(
                            new McpToolInterceptor(serverName, toolInfo.getOriginalName(), toolExecutor)))
                    .annotateMethod(AnnotationDescription.Builder.ofType(Tool.class)
                            .defineArray("value", description)
                            .build())
                    .make()
                    .load(getClass().getClassLoader())
                    .getLoaded();

            Object tool = toolClass.getDeclaredConstructor().newInstance();
            log.info("生成 MCP Tool: class={}, method={}, server={}, tool={}",
                    className, methodName, serverName, toolInfo.getOriginalName());
            return tool;
        } catch (Exception e) {
            throw new RuntimeException("生成 MCP Tool 失败: server=" + serverName
                    + ", tool=" + toolInfo.getOriginalName(), e);
        }
    }

    /**
     * 构建工具方法名：mcp_{sanitizedServerName}_{sanitizedToolName}
     * <p>
     * 业务含义：方法名加 mcp_ 前缀标识来源，加 {serverName}_ 二级前缀避免不同 Server 同名工具冲突。
     * 同时对 serverName/toolName 中的非法 Java 标识符字符（如中划线）做 sanitize，保证方法名合法。
     * </p>
     */
    public String buildMethodName(String serverName, String toolName) {
        return "mcp_" + sanitizeIdentifier(serverName) + "_" + sanitizeIdentifier(toolName);
    }

    /**
     * 构建工具类名：com.agentdemo.mcp.tool.McpTool_{sanitizedServerName}_{sanitizedToolName}
     */
    public String buildClassName(String serverName, String toolName) {
        return "com.agentdemo.mcp.tool.McpTool_"
                + sanitizeIdentifier(serverName) + "_" + sanitizeIdentifier(toolName);
    }

    /**
     * 构建 @Tool 描述
     * <p>
     * 业务含义：描述中包含 Server 名、工具原始描述、参数说明，
     * 让 LLM 通过描述理解工具用途与参数格式。
     * 关键改进：解析 JSON Schema 提取参数名/类型/描述，生成结构化参数说明，
     * 并给出调用示例，避免 LLM 猜错参数名。
     * </p>
     */
    public String buildToolDescription(String serverName, McpToolInfo toolInfo) {
        StringBuilder sb = new StringBuilder();
        sb.append("[MCP Server: ").append(serverName).append("] ");
        sb.append("工具名: ").append(toolInfo.getOriginalName()).append(". ");
        sb.append(toolInfo.getDescription());
        sb.append(" 适用场景：当用户的问题需要此工具提供的能力时调用。");

        // 解析参数 schema，生成结构化参数说明
        String paramDescription = parseParametersSchema(toolInfo.getParametersSchema());
        sb.append(paramDescription);

        return sb.toString();
    }

    /**
     * 解析 JSON Schema，生成 LLM 易于理解的参数说明
     * <p>
     * 业务含义：从 MCP Server 返回的参数 JSON Schema 中提取参数名、类型、描述、是否必填，
     * 格式化为清晰的参数列表和调用示例，避免 LLM 猜错参数名。
     * </p>
     */
    private String parseParametersSchema(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return ". argsJson 参数为 JSON 字符串: {}";
        }

        try {
            JsonNode schema = objectMapper.readTree(schemaJson);
            JsonNode properties = schema.get("properties");
            if (properties == null || properties.isEmpty()) {
                return ". argsJson 参数为 JSON 字符串: {}";
            }

            // 提取必填参数列表
            List<String> requiredParams = new ArrayList<>();
            JsonNode requiredNode = schema.get("required");
            if (requiredNode != null && requiredNode.isArray()) {
                for (JsonNode req : requiredNode) {
                    requiredParams.add(req.asText());
                }
            }

            StringBuilder sb = new StringBuilder();
            sb.append("\n\n调用方式：将以下参数封装为一个 JSON 对象字符串，传入 argsJson 参数。");
            sb.append("\n参数列表：");

            Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String paramName = entry.getKey();
                JsonNode paramSchema = entry.getValue();

                String type = paramSchema.has("type") ? paramSchema.get("type").asText() : "any";
                String desc = paramSchema.has("description") ? paramSchema.get("description").asText() : "";
                boolean required = requiredParams.contains(paramName);

                sb.append("\n  - ").append(paramName);
                sb.append(" (").append(type);
                sb.append(required ? ", required" : ", optional");
                sb.append(")");
                if (!desc.isEmpty()) {
                    sb.append(": ").append(desc);
                }
            }

            // 生成调用示例
            sb.append("\n\n示例 argsJson: {");
            boolean first = true;
            for (String param : requiredParams) {
                if (!first) sb.append(", ");
                sb.append("\"").append(param).append("\": \"<你的输入>\"");
                first = false;
            }
            if (first && !properties.isEmpty()) {
                // 没有 required 字段时，取第一个属性作为示例
                String firstParam = properties.fieldNames().next();
                sb.append("\"").append(firstParam).append("\": \"<你的输入>\"");
            }
            sb.append("}");

            return sb.toString();
        } catch (Exception e) {
            log.warn("解析参数 Schema 失败，使用原始 schema: {}", e.getMessage());
            return ". argsJson 参数为 JSON 字符串: " + schemaJson;
        }
    }

    /**
     * 将字符串中的非 Java 标识符字符替换为下划线
     * <p>
     * 业务含义：保证 ByteBuddy 生成的方法名/类名为合法 Java 标识符。
     * 例如 "my-server" → "my_server"。
     * </p>
     */
    private String sanitizeIdentifier(String name) {
        if (name == null || name.isEmpty()) {
            return "_";
        }
        return name.replaceAll("[^a-zA-Z0-9_]", "_");
    }
}
