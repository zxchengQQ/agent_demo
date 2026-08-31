package com.agentdemo.tools.registry;

import com.agentdemo.observability.TraceCollector;
import com.agentdemo.tools.permission.ToolPermissionLevel;
import com.agentdemo.tools.permission.ToolPermissionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
 * <p>
 * 权限执行期兜底（AC-S01）：execute 入口先查权限，deny 工具直接返回拒绝文案且方法体零触发，
 * 覆盖提示注入诱导与旁路调用（纵深防御第二道防线，加载期过滤为第一道）。
 * </p>
 */
@Component
public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    /**
     * 执行期权限检查结果（技术方案 §3.4）
     * <p>
     * 业务含义：供 HITLReActStream 在拦截 ask 级工具时获取完整元数据
     * （权限等级 + toolId + 工具描述，描述用于确认卡片展示）。
     * </p>
     *
     * @param level          权限等级
     * @param toolId         工具标识（category:name）
     * @param toolDescription 工具描述（@Tool 注解 value）
     */
    public record ToolPermissionCheck(ToolPermissionLevel level, String toolId, String toolDescription) {}

    /**
     * deny 兜底拒绝文案（AC-S01）
     * <p>
     * 业务含义：固定代码注入，仅说明工具被禁用，不暴露任何权限配置细节
     * （配置者/配置时间/权限等级来源等），LLM 据此向用户说明无法完成或调整方案。
     * </p>
     */
    private static final String DENY_MESSAGE = "该工具已被禁止调用，无法执行。请告知用户无法完成此操作，或调整方案。";

    private final ToolRegistry toolRegistry;
    private final ToolPermissionService toolPermissionService;
    private final TraceCollector traceCollector;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ToolExecutor(ToolRegistry toolRegistry, ToolPermissionService toolPermissionService,
                        TraceCollector traceCollector) {
        this.toolRegistry = toolRegistry;
        this.toolPermissionService = toolPermissionService;
        this.traceCollector = traceCollector;
    }

    /**
     * 执行工具调用
     * 业务含义：ReAct 循环中 LLM 返回 tool_calls 时，通过此方法执行对应工具。
     * 入口先做执行期权限兜底（deny 直接拒绝，方法体不触发）；其余逻辑与现状一致。
     * 异常处理：工具执行失败时返回错误信息字符串，不抛出异常（AC-012）
     *
     * @param toolName      工具名（对应 @Tool 注解方法的方法名）
     * @param argumentsJson 参数 JSON 字符串（LLM 生成）
     * @return 工具执行结果字符串；工具不存在/执行失败/权限禁止时返回对应的提示信息
     */
    public String execute(String toolName, String argumentsJson) {
        // 业务含义：执行期 deny 兜底拦截——即便工具绕过加载期过滤（提示注入诱导/旁路调用），
        // 也在方法体触发前再次校验，deny 工具方法体零触发（AC-S01，纵深防御第二道防线）。
        // 埋点：工具调用（含成败）统一在返回前采集（AC-N04/T03）。
        long startNanos = System.nanoTime();
        ToolPermissionCheck check = checkPermission(toolName);
        if (check.level() == ToolPermissionLevel.DENY) {
            recordTool(toolName, argumentsJson, DENY_MESSAGE, startNanos, false, DENY_MESSAGE);
            return DENY_MESSAGE;
        }
        try {
            // 1. 查找工具方法
            MethodAndBean target = findToolMethod(toolName);
            if (target == null) {
                String msg = "工具不存在: " + toolName + "。可用工具: " + listAvailableToolNames();
                recordTool(toolName, argumentsJson, msg, startNanos, false, msg);
                return msg;
            }

            // 2. 解析参数 JSON
            JsonNode argsNode = objectMapper.readTree(argumentsJson);
            Object[] args = buildMethodArguments(target.method, argsNode);

            // 3. 反射调用
            Object result = target.method.invoke(target.bean, args);
            String resultStr = result != null ? result.toString() : "null";
            recordTool(toolName, argumentsJson, resultStr, startNanos, true, null);
            return resultStr;
        } catch (Exception e) {
            // 工具执行失败：返回错误信息，不抛出异常
            // 反射调用抛出 InvocationTargetException 时，真实异常在 getCause() 中
            String errorMsg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            // AC-T03：失败也记录（含异常信息），再返回错误信息
            recordTool(toolName, argumentsJson, null, startNanos, false, errorMsg);
            return "工具执行失败: " + errorMsg;
        }
    }

    /**
     * 采集工具调用事件（AC-N04 五要素：工具名/入参/出参/耗时/成败；AC-T03 失败含异常信息）
     * <p>
     * 业务含义：旁路采集，内部 try-catch 保证采集失败不影响工具执行主流程（AC-S04）。
     * </p>
     */
    private void recordTool(String toolName, String argumentsJson, String result,
                            long startNanos, boolean success, String errorMessage) {
        try {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            traceCollector.recordTool(new TraceCollector.ToolCallEvent(
                    toolName, argumentsJson, result, durationMs, success, errorMessage));
        } catch (Exception e) {
            // AC-S04：采集失败仅 WARN，不影响工具执行
            log.warn("LangSmith 工具采集失败（降级跳过）: tool={}, error={}", toolName, e.getMessage());
        }
    }

    /**
     * 执行前权限检查（供 HITLReActStream 拦截判断）
     * <p>
     * 业务含义：将工具方法名映射为 toolId 后查询权限等级（复用 ToolRegistry.getToolMeta，
     * 保证 methodName→toolId 与加载期标识体系一致）。未知工具名保守返回 ASK（不抛异常），
     * 由加载期/执行期各自按保守策略处理；权限功能关闭（enabled=false）时 getPermission 返回 ALLOW。
     * </p>
     *
     * @param toolMethodName 工具方法名（@Tool 方法名）
     * @return 权限检查结果（level 永不返回 null）
     */
    public ToolPermissionCheck checkPermission(String toolMethodName) {
        Optional<ToolRegistry.ToolMeta> meta = toolRegistry.getToolMeta(toolMethodName);
        if (meta.isEmpty()) {
            // 业务含义：未知工具不抛异常，保守返回 ASK（可走 ask 拦截或由调用方继续走"工具不存在"分支）
            return new ToolPermissionCheck(ToolPermissionLevel.ASK, null, null);
        }
        ToolPermissionLevel level = toolPermissionService.getPermission(meta.get().toolId());
        return new ToolPermissionCheck(level, meta.get().toolId(), meta.get().description());
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
