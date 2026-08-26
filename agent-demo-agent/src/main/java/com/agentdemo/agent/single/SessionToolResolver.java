package com.agentdemo.agent.single;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.tools.registry.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级工具解析器（unified-chat-mode 新增，自 SimpleAgent 抽取）
 * <p>
 * 业务含义：统一对话模式下直答路径与拆解子任务路径共用同一工具解析管道
 * （技术方案决策 1：抽取共享组件消重）。负责：
 * 1. 会话级工具绑定缓存（首次指定 tools 后缓存，后续轮次无需重复指定）
 * 2. 默认工具合并（默认工具不可排除，BR-AGT-011/012）
 * 3. askUser 工具补入（HITL 能力的前提）
 * 4. 按 @Tool 方法名去重（避免重复工具定义导致 LLM 困惑）
 * </p>
 * <p>
 * 调用方：SimpleAgent（同步对话）、UnifiedChatStream（统一模式编排）
 * </p>
 */
@Service
public class SessionToolResolver {

    private static final Logger log = LoggerFactory.getLogger(SessionToolResolver.class);

    private final ToolRegistry toolRegistry;
    private final AgentConfig agentConfig;

    /**
     * 会话级工具缓存（按 sessionId 隔离）
     * 业务含义：用户首次指定 tools 后缓存到会话，后续轮次无需重复指定。
     * 空数组时清除缓存，恢复仅默认工具。
     */
    private final ConcurrentHashMap<String, List<String>> sessionToolIds = new ConcurrentHashMap<>();

    public SessionToolResolver(ToolRegistry toolRegistry, AgentConfig agentConfig) {
        this.toolRegistry = toolRegistry;
        this.agentConfig = agentConfig;
    }

    /**
     * 解析会话工具列表
     * <p>
     * 业务含义：根据传入的 toolIds 决定本次对话的工具列表。
     * null -> 从会话缓存读取（无缓存时用默认）；非空列表 -> 解析并缓存；空列表 -> 清除缓存，仅默认。
     * </p>
     *
     * @param sessionId 会话 ID
     * @param toolIds   工具标识列表（null=沿用缓存，空=清除，非空=指定）
     * @return 本次对话绑定的工具对象列表（默认 ∪ 指定）
     */
    public List<Object> resolveSessionTools(String sessionId, List<String> toolIds) {
        return resolveSessionTools(sessionId, toolIds, true);
    }

    /**
     * 解析会话工具列表（支持路径感知过滤）
     * <p>
     * 业务含义：askSupported 区分加载路径——同步路径（SimpleAgent.chat）无暂停能力，
     * 走 ForDirect（剔除 deny + ask）；流式路径（chatStream/UnifiedChatStream）可走确认流程，
     * 走 ForStreaming（剔除 deny、保留 ask）。调用方只声明能力，权限语义由工具域裁决
     * （技术方案 §3.1）。过滤发生在缓存解析之后，会话缓存（sessionToolIds）不受影响。
     * </p>
     *
     * @param sessionId    会话 ID
     * @param toolIds      工具标识列表（null=沿用缓存，空=清除，非空=指定）
     * @param askSupported 是否支持 ask 级工具的暂停确认（false=同步路径，剔除 ask）
     * @return 本次对话绑定的工具对象列表（默认 ∪ 指定）
     */
    public List<Object> resolveSessionTools(String sessionId, List<String> toolIds, boolean askSupported) {
        List<String> effectiveIds;
        if (toolIds != null) {
            if (toolIds.isEmpty()) {
                // 空数组 -> 清除会话绑定，仅默认工具
                sessionToolIds.remove(sessionId);
                effectiveIds = null;
            } else {
                // 指定工具 -> 解析 + 缓存
                sessionToolIds.put(sessionId, toolIds);
                effectiveIds = toolIds;
            }
        } else {
            // 未指定 -> 从会话缓存读取
            effectiveIds = sessionToolIds.get(sessionId);
        }

        List<String> defaultIds = agentConfig.getTools().getDefaultTools();
        List<Object> tools;
        if (effectiveIds != null && !effectiveIds.isEmpty()) {
            // 业务含义：按调用方能力分流——可暂停路径保留 ask 工具，无暂停能力路径剔除 deny + ask
            tools = askSupported
                    ? toolRegistry.resolveToolsForStreaming(effectiveIds)
                    : toolRegistry.resolveToolsForDirect(effectiveIds);
        } else {
            tools = askSupported
                    ? toolRegistry.getDefaultToolsForStreaming(defaultIds)
                    : toolRegistry.getDefaultToolsForDirect(defaultIds);
        }
        // 合并默认工具（默认工具不可排除，同样应用能力声明过滤）
        return mergeDefaults(tools, askSupported);
    }

    /**
     * 确保工具列表包含 askUser 工具
     * <p>
     * 业务含义：askUser 是 HITL 模式的核心工具，但不在默认工具列表中。
     * 若用户未显式指定则动态加入，保证 LLM 始终能调用 askUser 向用户提问。
     * askUser 由权限服务豁免恒 ALLOW，任一路径（ForStreaming/ForDirect）均可用。
     * 已包含时（用户显式指定或已存在）直接返回原列表。
     * </p>
     *
     * @param tools 解析后的工具列表
     * @return 包含 askUser 工具的工具列表（已按方法名去重）
     */
    public List<Object> ensureAskUserTool(List<Object> tools) {
        boolean hasAskUser = tools.stream().anyMatch(t -> findToolMethodNames(t).contains("askUser"));
        if (!hasAskUser) {
            try {
                // askUser 豁免恒 ALLOW，能力方法均可解析（ForStreaming 语义：HITL 场景）
                List<Object> askUserTool = toolRegistry.resolveToolsForStreaming(List.of("builtin:askUser"));
                tools = new ArrayList<>(tools);
                tools.addAll(askUserTool);
                log.info("HITL 模式自动加入 askUser 工具");
            } catch (Exception e) {
                log.warn("askUser 工具加载失败: {}", e.getMessage());
            }
        }
        // 业务含义：按 @Tool 方法名去重（mergeDefaults 可能因对象实例不同产生重复工具），
        // 避免重复工具定义导致 LLM 困惑
        return dedupeToolsByMethodName(tools);
    }

    /**
     * 按 @Tool 方法名去重工具列表
     * <p>
     * 业务含义：不同途径（默认工具解析、会话缓存、动态加入）可能返回同一工具的
     * 不同实例，按方法名去重保证每个工具只出现一次，避免 tools JSON 冗余。
     * </p>
     *
     * @param tools 原始工具列表
     * @return 去重后的工具列表
     */
    public List<Object> dedupeToolsByMethodName(List<Object> tools) {
        if (tools == null || tools.isEmpty()) {
            return tools;
        }
        Set<String> seen = new HashSet<>();
        List<Object> result = new ArrayList<>();
        for (Object tool : tools) {
            List<String> methodNames = findToolMethodNames(tool);
            // 业务含义：工具对象可能含多个 @Tool 方法（如 MCP 代理对象），
            // 只要任一方法名未出现过则保留整个对象
            boolean allSeen = methodNames.stream().allMatch(seen::contains);
            if (!allSeen) {
                result.add(tool);
                seen.addAll(methodNames);
            }
        }
        return result;
    }

    /**
     * 获取工具对象的所有 @Tool 方法名
     * <p>
     * 公开供 SimpleAgent 复用（delegate 缓存指纹计算）。
     * </p>
     */
    public List<String> findToolMethodNames(Object tool) {
        List<String> names = new ArrayList<>();
        for (Method method : tool.getClass().getDeclaredMethods()) {
            if (method.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class)) {
                names.add(method.getName());
            }
        }
        return names;
    }

    /**
     * 合并默认工具，保证默认工具始终在列表中（默认工具同样应用能力声明过滤）
     */
    private List<Object> mergeDefaults(List<Object> specifiedTools, boolean askSupported) {
        List<String> defaultIds = agentConfig.getTools().getDefaultTools();
        if (defaultIds == null || defaultIds.isEmpty()) {
            return specifiedTools;
        }
        List<Object> defaultTools = askSupported
                ? toolRegistry.getDefaultToolsForStreaming(defaultIds)
                : toolRegistry.getDefaultToolsForDirect(defaultIds);
        // 默认工具排在前面，去重
        List<Object> result = new ArrayList<>(defaultTools);
        for (Object t : specifiedTools) {
            if (!result.contains(t)) {
                result.add(t);
            }
        }
        return result;
    }
}
