package com.agentdemo.tools.registry;

import com.agentdemo.common.dto.ToolInfo;
import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.tools.permission.DefaultToolPermission;
import com.agentdemo.tools.permission.ToolPermissionGuard;
import com.agentdemo.tools.permission.ToolPermissionLevel;
import com.agentdemo.tools.permission.ToolPermissionService;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
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

    /**
     * 工具元数据（methodName → toolId + 描述）
     * <p>
     * 业务含义：供 ToolExecutor 在执行期做 methodName → toolId 的权限映射，
     * 复用本类的类别推断与 toolId 构建逻辑，避免两套标识逻辑漂移。
     * </p>
     */
    public record ToolMeta(String toolId, String description) {}

    private final ApplicationContext applicationContext;

    private final ToolPermissionService toolPermissionService;

    /** 工具标识解析器（toolId 构建内聚，供权限捕获/过滤共用） */
    private final ToolIdResolver toolIdResolver;

    /** 出口统一包装层（AC-T04：执行期 deny 零触发防线，双方法分发时逐工具包装） */
    private final ToolPermissionGuard toolPermissionGuard;

    /**
     * 工具列表（CopyOnWriteArrayList 保证读多写少场景的线程安全）
     */
    private final CopyOnWriteArrayList<Object> tools = new CopyOnWriteArrayList<>();

    /**
     * 是否已扫描标记（volatile 保证可见性，懒加载双重检查锁）
     */
    private volatile boolean scanned = false;

    public ToolRegistry(ApplicationContext applicationContext,
                        ToolPermissionService toolPermissionService,
                        ToolIdResolver toolIdResolver,
                        ToolPermissionGuard toolPermissionGuard) {
        this.applicationContext = applicationContext;
        this.toolPermissionService = toolPermissionService;
        this.toolIdResolver = toolIdResolver;
        this.toolPermissionGuard = toolPermissionGuard;
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
                registerDefaultPermissions(bean);
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
            registerDefaultPermissions(tool);
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
                toolIdResolver.registerServerName(method.getName(), serverName);
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
        // 业务含义：先收集被移除工具对象全部 @Tool 方法的 toolId，
        // 再联动清理权限登记（显式配置 + 默认登记），避免孤儿权限配置（AC-E01）。
        List<String> removedToolIds = new ArrayList<>();
        tools.removeIf(tool -> {
            if (hasToolMethod(tool, toolName)) {
                collectToolIds(tool, removedToolIds);
                return true;
            }
            return false;
        });
        if (!removedToolIds.isEmpty()) {
            toolPermissionService.clear(removedToolIds);
        }
        log.info("动态注销工具: {}", toolName);
    }

    /**
     * 收集工具对象全部 @Tool 方法的 toolId（供注销时权限清理）
     */
    private void collectToolIds(Object tool, List<String> toolIds) {
        for (Method method : findToolMethods(tool.getClass())) {
            String category = toolIdResolver.inferCategory(method.getName());
            toolIds.add(toolIdResolver.buildToolId(category, method.getName()));
        }
    }

    /**
     * 登记工具对象的默认权限等级
     * <p>
     * 业务含义：扫描与动态注册共用此入口。内置工具读 {@link DefaultToolPermission} 注解；
     * 动态工具按类别兜底（mcp→ASK / rag→ALLOW，技术方案 §3.5）。
     * </p>
     */
    private void registerDefaultPermissions(Object tool) {
        for (Method method : findToolMethods(tool.getClass())) {
            String methodName = method.getName();
            String category = toolIdResolver.inferCategory(methodName);
            String toolId = toolIdResolver.buildToolId(category, methodName);
            toolPermissionService.registerDefault(toolId, defaultLevelFor(tool.getClass(), category));
        }
    }

    /**
     * 计算工具默认权限等级：类别兜底优先于注解（mcp→ASK / rag→ALLOW），内置工具读注解值，未标注按保守 ASK
     */
    private ToolPermissionLevel defaultLevelFor(Class<?> clazz, String category) {
        if ("mcp".equals(category)) {
            return ToolPermissionLevel.ASK;
        }
        if ("rag".equals(category)) {
            return ToolPermissionLevel.ALLOW;
        }
        DefaultToolPermission annotation = clazz.getAnnotation(DefaultToolPermission.class);
        return annotation != null ? annotation.value() : ToolPermissionLevel.ASK;
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
    public List<Object> resolveToolsForStreaming(List<String> identifiers) {
        return wrapAll(resolveAndFilter(identifiers, tool -> isExcludedByPermission(tool, true)));
    }

    /**
     * 能力声明双方法：调用方无暂停-恢复能力（同步/非HITL路径），deny + ask 均剔除
     * <p>
     * 业务含义：ForDirect = 原 SYNC 语义（剔除 deny + ask，ask 无确认通道故不注入，
     * 修复工作流非 HITL 路径 ask 卡死/直执行，AC-E01）。
     * </p>
     *
     * @param identifiers 工具标识列表（category:name 格式）
     * @return 解析、过滤并包装后的工具对象列表
     */
    public List<Object> resolveToolsForDirect(List<String> identifiers) {
        return wrapAll(resolveAndFilter(identifiers, tool -> isExcludedByPermission(tool, false)));
    }

    /**
     * 出口统一包装（AC-T04：执行链路统一）
     * <p>
     * 业务含义：所有经能力声明双方法分发出去的工具均携带权限包装——LangChain4j 反射直调
     * 命中包装层拦截器（deny 方法体零触发），实现"加载期过滤 + 执行期兜底"双闸门。
     * getAvailableTools 走 ToolInfo 构建不在此路径，管理页展示不受影响。
     * </p>
     */
    private List<Object> wrapAll(List<Object> resolved) {
        return resolved.stream().map(toolPermissionGuard::wrap).collect(Collectors.toList());
    }

    /**
     * 解析 + 应用权限谓词过滤（能力声明双方法共用的解析骨架，AC-T01 唯一解析出口）
     */
    private List<Object> resolveAndFilter(List<String> identifiers, Predicate<Object> filter) {
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

        result.removeIf(filter);
        return new ArrayList<>(result);
    }

    /**
     * 判断工具对象是否应被权限过滤剔除（boolean 能力声明版）
     * <p>
     * 业务含义：任一 @Tool 方法命中 DENY 则整体剔除（防御一致性）；
     * askVisible=false 时任一方法为 ASK 亦剔除（无暂停能力路径）。
     * askUser 因权限服务豁免返回 ALLOW，永不被剔除（AC-S03）。
     * </p>
     *
     * @param tool       工具对象
     * @param askVisible 调用方是否具备暂停-恢复能力（true=ask 可见，false=ask 剔除）
     * @return true=应剔除
     */
    private boolean isExcludedByPermission(Object tool, boolean askVisible) {
        for (Method method : findToolMethods(tool.getClass())) {
            String category = toolIdResolver.inferCategory(method.getName());
            String toolId = toolIdResolver.buildToolId(category, method.getName());
            ToolPermissionLevel level = toolPermissionService.getPermission(toolId);
            if (level == ToolPermissionLevel.DENY) {
                return true;
            }
            if (!askVisible && level == ToolPermissionLevel.ASK) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按 @Tool 方法名查询工具元数据（toolId + 描述）
     * <p>
     * 业务含义：供 ToolExecutor 在执行期做 methodName → toolId 映射以查权限，
     * 复用本类 inferCategory/buildToolId/getToolDescription 逻辑，避免标识体系漂移（技术方案 §3.4）。
     * </p>
     *
     * @param methodName 工具方法名
     * @return 工具元数据（未找到返回 Optional.empty，不抛异常）
     */
    public Optional<ToolMeta> getToolMeta(String methodName) {
        if (methodName == null || methodName.isBlank()) {
            return Optional.empty();
        }
        for (Object tool : tools) {
            for (Method method : findToolMethods(tool.getClass())) {
                if (methodName.equals(method.getName())) {
                    String category = toolIdResolver.inferCategory(methodName);
                    String toolId = toolIdResolver.buildToolId(category, methodName);
                    return Optional.of(new ToolMeta(toolId, getToolDescription(method)));
                }
            }
        }
        return Optional.empty();
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
            for (String methodName : toolIdResolver.methodNamesOfServer(serverName)) {
                Object matchedTool = findToolByMethodName(methodName);
                if (matchedTool != null) {
                    result.add(matchedTool);
                    found = true;
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
     * 每项包含 id（category:name）、category、name、description、isDefault、permission。
     * permission 来自 ToolPermissionService 裁决结果（AC-H02：前端选择器/管理页据此过滤与展示）。
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
                String category = toolIdResolver.inferCategory(methodName);
                String id = toolIdResolver.buildToolId(category, methodName);
                String description = getToolDescription(method);

                result.add(ToolInfo.builder()
                        .id(id)
                        .category(category)
                        .name(methodName)
                        .description(description)
                        .isDefault(defaultSet.contains(id))
                        .permission(toolPermissionService.getPermission(id).getCode())
                        .build());
            }
        }
        return result;
    }

    /**
     * 获取默认工具对象列表（能力声明：可暂停确认路径，deny 剔除、ask 保留）
     */
    public List<Object> getDefaultToolsForStreaming(List<String> defaultToolIds) {
        return wrapAll(getDefaultTools(defaultToolIds, tool -> isExcludedByPermission(tool, true)));
    }

    /**
     * 获取默认工具对象列表（能力声明：无暂停能力路径，deny + ask 均剔除，出口统一包装）
     */
    public List<Object> getDefaultToolsForDirect(List<String> defaultToolIds) {
        return wrapAll(getDefaultTools(defaultToolIds, tool -> isExcludedByPermission(tool, false)));
    }

    /**
     * 获取默认工具对象列表（能力声明谓词过滤私有版，双方法共用）
     * <p>
     * 业务含义：同一工具 Bean 可能被多个 id 命中（如 TimeTool 同时含
     * getCurrentTime/getCurrentTimeByZone/getCurrentDate 三个 @Tool 方法），
     * 若逐 id 直接 addAll 会导致同一实例重复加入，AiServices 构建时报
     * "Duplicated definition for tool"。故用 LinkedHashSet 按对象去重。
     * </p>
     */
    private List<Object> getDefaultTools(List<String> defaultToolIds, Predicate<Object> filter) {
        if (defaultToolIds == null || defaultToolIds.isEmpty()) {
            return List.of();
        }
        Set<Object> result = new LinkedHashSet<>();
        for (String id : defaultToolIds) {
            try {
                result.addAll(resolveAndFilter(List.of(id), filter));
            } catch (BusinessException e) {
                log.error("默认工具不存在: {}，跳过加载", id);
            }
        }
        return new ArrayList<>(result);
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
