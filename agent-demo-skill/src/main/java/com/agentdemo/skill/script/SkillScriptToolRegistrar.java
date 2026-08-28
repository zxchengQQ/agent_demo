package com.agentdemo.skill.script;

import com.agentdemo.skill.entity.SkillDefinition;
import com.agentdemo.tools.registry.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 脚本工具注册器（CR-001 Task-33，AC-T02/T03/T05）
 * <p>
 * 业务含义：为技能激活提供其自带脚本工具（skill_{skillId}_{scriptName}），并注册进
 * ToolRegistry 供 ToolExecutor 执行。设计要点（决策 9，AC-T03）：脚本工具类标注
 * {@code @DefaultToolPermission(ALLOW)}——默认自主执行（不弹确认卡），安全由 SkillScriptExecutor
 * 脚本护栏独立管控（语言白名单/参数校验/超时/危险命令拦截），不依赖系统权限模型。
 * </p>
 * <p>
 * 生命周期（AC-T05）：ensureRegistered 在技能激活时调用（幂等，同一脚本只注册一次）；
 * unregisterSkill 在技能删除时注销并清缓存；技能退出激活集后由 SessionToolResolver 不再
 * 注入其脚本工具（会话级可见性），全局注册不残留调用面。
 * </p>
 */
@Component
public class SkillScriptToolRegistrar {

    private static final Logger log = LoggerFactory.getLogger(SkillScriptToolRegistrar.class);

    private final ToolRegistry toolRegistry;
    private final SkillScriptExecutor scriptExecutor;

    /** 已生成脚本工具缓存（key: skill_{skillId}_{scriptName}） */
    private final Map<String, Object> toolCache = new ConcurrentHashMap<>();

    /** 已注册进 ToolRegistry 的工具名（避免重复注册同一实例） */
    private final Set<String> registeredTools = ConcurrentHashMap.newKeySet();

    public SkillScriptToolRegistrar(ToolRegistry toolRegistry, SkillScriptExecutor scriptExecutor) {
        this.toolRegistry = toolRegistry;
        this.scriptExecutor = scriptExecutor;
    }

    /**
     * 确保技能脚本工具已生成并注册进 ToolRegistry（技能激活时调用，幂等）
     *
     * @param skill 已激活技能
     * @return 脚本工具对象列表（无脚本返回空列表）
     */
    public List<Object> ensureRegistered(SkillDefinition skill) {
        if (skill == null || skill.getScripts() == null || skill.getScripts().isEmpty()) {
            return List.of();
        }
        List<Object> tools = new ArrayList<>();
        for (var script : skill.getScripts()) {
            if (script == null || script.getName() == null || script.getName().isBlank()) {
                continue;
            }
            String toolName = buildToolName(skill.getId(), script.getName());
            Object tool = toolCache.computeIfAbsent(toolName, k -> {
                log.info("脚本工具首次生成: {}（skill={}）", k, skill.getId());
                return SkillScriptToolFactory.createTool(skill, script, scriptExecutor);
            });
            if (registeredTools.add(toolName)) {
                // 首次注册进 ToolRegistry（ToolExecutor 执行路径），@DefaultToolPermission(ALLOW) 默认自主执行
                toolRegistry.register(tool, "skill");
                log.info("脚本工具注册进 ToolRegistry: {}", toolName);
            }
            tools.add(tool);
        }
        return tools;
    }

    /**
     * 注销技能全部脚本工具（技能删除时调用：ToolRegistry 注销 + 清缓存）
     *
     * @param skillId 技能 id
     */
    public void unregisterSkill(String skillId) {
        String prefix = "skill_" + skillId + "_";
        List<String> toRemove = toolCache.keySet().stream().filter(k -> k.startsWith(prefix)).toList();
        for (String toolName : toRemove) {
            toolRegistry.unregisterTool(toolName);
            registeredTools.remove(toolName);
        }
        toRemove.forEach(toolCache::remove);
    }

    /**
     * 脚本工具名：skill_{skillId}_{scriptName}（非标识符字符替换为下划线，保证 Java 方法名合法）
     */
    public static String buildToolName(String skillId, String scriptName) {
        return "skill_" + sanitize(skillId) + "_" + sanitize(scriptName);
    }

    private static String sanitize(String s) {
        return s == null ? "" : s.replaceAll("[^a-zA-Z0-9_]", "_");
    }
}
