package com.agentdemo.tools.registry;

import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

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
}
