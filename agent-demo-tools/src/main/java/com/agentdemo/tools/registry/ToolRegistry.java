package com.agentdemo.tools.registry;

import com.agentdemo.common.dto.ToolInfo;
import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 工具注册中心
 * <p>
 * 业务含义：集中管理所有 Agent 可调用的工具，Spring 启动后懒加载扫描带 @Tool 注解的 Bean。
 * 调用方：agent 层（构建 AiServices 时调用 listTools 获取工具列表）
 * </p>
 * <p>
 * 设计原则：
 * 1. 懒加载：不在构造函数中扫描，避免循环依赖（SimpleAgent 依赖 ToolRegistry，构造时扫描会触发 SimpleAgent 初始化）
 * 2. 声明式注册：工具类加 @Component + 方法加 @Tool，首次调用 listTools 时自动扫描
 * 3. 动态注册：支持运行时新增工具（如 MCP 加载的外部工具）
 * 4. 线程安全：使用 CopyOnWriteArrayList 存储工具列表
 * </p>
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final ApplicationContext applicationContext;

    /**
     * 工具列表（CopyOnWriteArrayList 保证读多写少场景的线程安全）
     */
    private final CopyOnWriteArrayList<Object> tools = new CopyOnWriteArrayList<>();

    /**
     * MCP 工具方法名 → MCP Server 名称 映射
     * 业务含义：MCP 工具通过 register(tool, serverName) 登记，用于生成准确的 mcp:{serverName} 标识。
     */
    private final Map<String, String> serverNamesByMethod = new HashMap<>();

    /**
     * 是否已扫描标记（volatile 保证可见性，懒加载双重检查锁）
     */
    private volatile boolean scanned = false;

    public ToolRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * 懒加载扫描工具
     * 业务含义：首次调用 listTools 时扫描所有 @Component Bean，避免构造时循环依赖
     */
    private void ensureScanned() {
        if (!scanned) {
            synchronized (this) {
                if (!scanned) {
                    scanTools();
                    scanned = true;
                }
            }
        }
    }

    /**
     * 扫描所有带 @Tool 注解的 Spring Bean
     */
    private void scanTools() {
        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(org.springframework.stereotype.Component.class);
        for (Map.Entry<String, Object> entry : beans.entrySet()) {
            Object bean = entry.getValue();
            if (hasToolAnnotation(bean.getClass())) {
                tools.add(bean);
                log.info("注册工具: {} ({})", bean.getClass().getSimpleName(), entry.getKey());
            }
        }
        log.info("工具注册完成，共注册 {} 个工具", tools.size());
    }

    /**
     * 检查类中是否有 @Tool 注解的方法
     */
    private boolean hasToolAnnotation(Class<?> clazz) {
        return !findToolMethods(clazz).isEmpty();
    }

    /**
     * 获取类中所有标注了 @Tool 的方法
     */
    private List<Method> findToolMethods(Class<?> clazz) {
        List<Method> toolMethods = new ArrayList<>();
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Tool.class)) {
                toolMethods.add(method);
            }
        }
        return toolMethods;
    }

    /**
     * 获取所有已注册工具
     * 调用方：agent 层构建 AiServices 时调用
     *
     * @return 工具列表
     */
    public List<Object> listTools() {
        ensureScanned();
        return new ArrayList<>(tools);
    }

    /**
     * 按类名获取工具
     *
     * @param name 工具类简单名
     * @return 工具对象（不存在返回 null）
     */
    public Object getTool(String name) {
        ensureScanned();
        return tools.stream()
                .filter(t -> t.getClass().getSimpleName().equals(name))
                .findFirst()
                .orElse(null);
    }

    /**
     * 动态注册工具
     * 业务含义：运行时新增工具（如 MCP 加载的外部工具），注册后立即可被 Agent 使用
     *
     * @param tool 工具对象
     */
    public void register(Object tool) {
        if (tool != null && hasToolAnnotation(tool.getClass())) {
            tools.add(tool);
            log.info("动态注册工具: {}", tool.getClass().getSimpleName());
        }
    }

    /**
     * 动态注册 MCP 工具（带 serverName）
     * <p>
     * 业务含义：MCP 工具方法名格式为 mcp_{serverName}_{toolName}，toolName 可能含下划线，
     * 无法从方法名可靠反推 serverName。因此注册时显式登记 serverName，
     * 供 getAvailableTools/resolveTools 生成准确的 mcp:{serverName} 标识。
     * </p>
     *
     * @param tool 工具对象
     * @param serverName MCP Server 名称
     */
    public void register(Object tool, String serverName) {
        register(tool);
        if (tool != null && serverName != null && !serverName.isBlank()) {
            for (Method method : findToolMethods(tool.getClass())) {
                serverNamesByMethod.put(method.getName(), serverName);
            }
        }
    }

    /**
     * 动态注销工具
     * 业务含义：运行时移除工具（如 CR-003 知识库删除时注销对应 Tool），
     * 按工具方法名匹配并移除所有匹配的工具实例。
     *
     * @param toolName 工具方法名
     */
    public void unregisterTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return;
        }
        tools.removeIf(tool -> hasToolMethod(tool, toolName));
        log.info("动态注销工具: {}", toolName);
    }

    /**
     * 检查工具是否包含指定名称的 @Tool 方法
     */
    private boolean hasToolMethod(Object tool, String toolName) {
        return findToolMethods(tool.getClass()).stream()
                .anyMatch(method -> toolName.equals(method.getName()));
    }

    /**
     * 获取已注册工具数量
     *
     * @return 工具数量
     */
    public int size() {
        ensureScanned();
        return tools.size();
    }

    /**
     * 获取已注册工具数量（别名）
     *
     * @return 工具数量
     */
    public int getToolCount() {
        return size();
    }

    // ==================== 工具按需加载 ====================

    /**
     * 将 category:name 标识列表解析为工具对象列表
     * <p>
     * 业务含义：将前端/API 传入的工具标识（如 "mcp:mermaid-mcp"、"builtin:getCurrentTime"）
     * 解析为实际的工具 Bean 对象，供 Agent 绑定使用。
     * 支持通配符 "*" 展开整类工具（如 "mcp:*" 展开所有 MCP 工具）。
     * </p>
     *
     * @param identifiers 工具标识列表（category:name 格式）
     * @return 解析后的工具对象列表（去重）
     * @throws BusinessException 标识格式错误（TOOL_PARAM_INVALID）或工具不存在（TOOL_NOT_FOUND）
     */
    public List<Object> resolveTools(List<String> identifiers) {
        ensureScanned();
        Set<Object> result = new LinkedHashSet<>();

        for (String identifier : identifiers) {
            String[] parts = identifier.split(":", 2);
            if (parts.length != 2) {
                throw new BusinessException(ErrorCode.TOOL_PARAM_INVALID,
                        "工具标识格式错误: " + identifier + "，正确格式为 category:name（如 builtin:getCurrentTime）");
            }
            String category = parts[0];
            String name = parts[1];

            switch (category) {
                case "builtin":
                    resolveBuiltin(result, name, identifier);
                    break;
                case "mcp":
                    resolveMcp(result, name, identifier);
                    break;
                case "rag":
                    resolveRag(result, name, identifier);
                    break;
                default:
                    throw new BusinessException(ErrorCode.TOOL_PARAM_INVALID,
                            "工具标识格式错误: " + identifier + "，category 仅支持 builtin/mcp/rag");
            }
        }
        return new ArrayList<>(result);
    }

    /** 解析内置工具：按方法名查找 */
    private void resolveBuiltin(Set<Object> result, String methodName, String identifier) {
        for (Object tool : tools) {
            if (hasToolMethod(tool, methodName)) {
                result.add(tool);
                return;
            }
        }
        throw new BusinessException(ErrorCode.TOOL_NOT_FOUND,
                "工具不存在: " + identifier);
    }

    /** 解析 MCP 工具：按 MCP Server 名称匹配（优先用 serverNamesByMethod 登记信息） */
    private void resolveMcp(Set<Object> result, String serverName, String identifier) {
        if ("*".equals(serverName)) {
            // 通配符：展开所有 MCP 工具
            boolean found = false;
            for (Object tool : tools) {
                for (Method method : findToolMethods(tool.getClass())) {
                    if (method.getName().startsWith("mcp_")) {
                        result.add(tool);
                        found = true;
                        break;
                    }
                }
            }
            if (!found) {
                log.warn("未找到任何 MCP 工具");
            }
        } else {
            // 精确匹配：按 serverNamesByMethod 登记信息匹配
            boolean found = false;
            for (Map.Entry<String, String> entry : serverNamesByMethod.entrySet()) {
                if (serverName.equals(entry.getValue())) {
                    // 找到该 server 的方法名，定位工具对象
                    Object matchedTool = findToolByMethodName(entry.getKey());
                    if (matchedTool != null) {
                        result.add(matchedTool);
                        found = true;
                    }
                }
            }
            if (!found) {
                throw new BusinessException(ErrorCode.TOOL_NOT_FOUND,
                        "工具不存在: " + identifier);
            }
        }
    }

    /** 按 @Tool 方法名查找工具对象 */
    private Object findToolByMethodName(String methodName) {
        for (Object tool : tools) {
            if (hasToolMethod(tool, methodName)) {
                return tool;
            }
        }
        return null;
    }

    /** 解析知识库工具：按 kb_{kbId} 方法名匹配 */
    private void resolveRag(Set<Object> result, String kbId, String identifier) {
        String prefix = "kb_";
        if ("*".equals(kbId)) {
            // 通配符：展开所有知识库工具
            boolean found = false;
            for (Object tool : tools) {
                for (Method method : findToolMethods(tool.getClass())) {
                    if (method.getName().startsWith(prefix)) {
                        result.add(tool);
                        found = true;
                        break;
                    }
                }
            }
            if (!found) {
                log.warn("未找到任何知识库工具");
            }
        } else {
            // 精确匹配：kb_{kbId}
            String methodName = prefix + kbId;
            for (Object tool : tools) {
                if (hasToolMethod(tool, methodName)) {
                    result.add(tool);
                    return;
                }
            }
            throw new BusinessException(ErrorCode.TOOL_NOT_FOUND,
                    "工具不存在: " + identifier);
        }
    }

    /**
     * 获取所有可用工具信息（供前端展示）
     * <p>
     * 业务含义：遍历所有已注册工具，构建 ToolInfo 列表。
     * 每项包含 id（category:name）、category、name、description、isDefault。
     * </p>
     *
     * @param defaultToolIds 默认工具 ID 列表（用于判断 isDefault）
     * @return 工具信息列表
     */
    public List<ToolInfo> getAvailableTools(List<String> defaultToolIds) {
        ensureScanned();
        Set<String> defaultSet = defaultToolIds != null ? new LinkedHashSet<>(defaultToolIds) : Set.of();
        List<ToolInfo> result = new ArrayList<>();

        for (Object tool : tools) {
            for (Method method : findToolMethods(tool.getClass())) {
                String methodName = method.getName();
                String category = inferCategory(methodName);
                String id = buildToolId(category, methodName);
                String description = getToolDescription(method);

                result.add(ToolInfo.builder()
                        .id(id)
                        .category(category)
                        .name(methodName)
                        .description(description)
                        .isDefault(defaultSet.contains(id))
                        .build());
            }
        }
        return result;
    }

    /**
     * 获取默认工具对象列表
     *
     * @param defaultToolIds 默认工具 ID 列表
     * @return 默认工具对象列表
     */
    public List<Object> getDefaultTools(List<String> defaultToolIds) {
        if (defaultToolIds == null || defaultToolIds.isEmpty()) {
            return List.of();
        }
        List<Object> result = new ArrayList<>();
        for (String id : defaultToolIds) {
            try {
                result.addAll(resolveTools(List.of(id)));
            } catch (BusinessException e) {
                log.error("默认工具不存在: {}，跳过加载", id);
            }
        }
        return result;
    }

    /** 推断工具类别 */
    private String inferCategory(String methodName) {
        if (methodName.startsWith("mcp_")) {
            return "mcp";
        }
        if (methodName.startsWith("kb_")) {
            return "rag";
        }
        return "builtin";
    }

    /** 构建工具标识 id */
    private String buildToolId(String category, String methodName) {
        switch (category) {
            case "mcp":
                // 优先用登记的 serverName（工具名可能含下划线，无法从方法名可靠反推）
                String serverName = serverNamesByMethod.get(methodName);
                if (serverName != null && !serverName.isBlank()) {
                    return "mcp:" + serverName;
                }
                // 回退：方法名去掉 mcp_ 前缀取首段
                String noPrefix = methodName.substring(4);
                int firstUnderscore = noPrefix.indexOf('_');
                if (firstUnderscore > 0) {
                    return "mcp:" + noPrefix.substring(0, firstUnderscore);
                }
                return "mcp:" + noPrefix;
            case "rag":
                // kb_{kbId} → 提取 kbId
                return "rag:" + methodName.substring(3);
            default:
                return "builtin:" + methodName;
        }
    }

    /** 获取 @Tool 注解的描述 */
    private String getToolDescription(Method method) {
        Tool tool = method.getAnnotation(Tool.class);
        if (tool != null && tool.value() != null && tool.value().length > 0) {
            return String.join(" ", tool.value());
        }
        return method.getName();
    }
}
