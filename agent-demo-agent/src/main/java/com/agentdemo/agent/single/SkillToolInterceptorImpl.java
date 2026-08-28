package com.agentdemo.agent.single;

import com.agentdemo.skill.script.SkillScriptToolRegistrar;
import com.agentdemo.skill.session.SkillSessionManager;
import com.agentdemo.skill.tool.SkillLoadTool;
import com.agentdemo.tools.registry.ToolSchemaConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 技能拦截器实现（agent-skill 决策 4：流式路径 loadSkill 拦截协作）
 * <p>
 * 业务含义：为 HITLReActStream 提供 loadSkill 拦截的具体实现，组合：
 * SkillLoadTool（激活逻辑 + 观察值生成）、SkillSessionManager（激活态）、
 * SessionToolResolver + ToolSchemaConverter（工具热刷新：重解析会话工具集合并自带脚本工具）。
 * </p>
 * <p>
 * 热刷新机制（技术方案 3.2）：激活成功后重调 resolveSessionTools（含技能脚本工具合并）+
 * ensureAskUserTool + convertToJson，返回新 toolsJson；无新增脚本工具时返回 null（不刷新，省开销）。
 * </p>
 * <p>
 * 事件载荷（CR-001）：boundToolIds 语义改为"自带脚本工具名列表"（skill_{skillId}_{scriptName}），
 * 供 skill_activated SSE 事件向前端透出。
 * </p>
 */
@Component
public class SkillToolInterceptorImpl implements SkillToolInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SkillToolInterceptorImpl.class);

    private final SkillLoadTool skillLoadTool;
    private final SkillSessionManager skillSessionManager;
    private final SessionToolResolver sessionToolResolver;
    private final ToolSchemaConverter toolSchemaConverter;

    public SkillToolInterceptorImpl(SkillLoadTool skillLoadTool,
                                    SkillSessionManager skillSessionManager,
                                    SessionToolResolver sessionToolResolver,
                                    ToolSchemaConverter toolSchemaConverter) {
        this.skillLoadTool = skillLoadTool;
        this.skillSessionManager = skillSessionManager;
        this.sessionToolResolver = sessionToolResolver;
        this.toolSchemaConverter = toolSchemaConverter;
    }

    @Override
    public SkillInterceptionResult interceptLoadSkill(String sessionId, String skillName,
                                                      String currentToolsJson, int iteration) {
        // 1. 激活（复用 SkillLoadTool 观察值生成，同步/流式路径共享激活逻辑）
        String observation = skillLoadTool.loadSkill(sessionId, skillName);

        // 2. 判定激活是否成功：激活后技能出现在激活集
        boolean activated = false;
        String skillId = null;
        String skillDisplayName = null;
        List<String> boundToolIds = List.of();
        String refreshedToolsJson = null;

        if (observation != null && !observation.contains("技能激活失败")
                && !observation.contains("不存在") && !observation.contains("上限")
                && !observation.contains("排除") && !observation.contains("禁用")) {
            activated = true;
            skillId = resolveSkillId(skillName);
            skillDisplayName = skillName;
            if (skillId != null) {
                var skillOpt = skillSessionManager.getSkillDefinition(skillId);
                if (skillOpt.isPresent()) {
                    skillDisplayName = skillOpt.get().getName();
                    boundToolIds = scriptToolNames(skillId, skillOpt.get().getScripts());
                }
            }
            // 3. 热刷新：重解析会话工具集（含新激活技能脚本工具）→ 新 toolsJson
            refreshedToolsJson = refreshToolsJson(sessionId, currentToolsJson);
        }

        return new SkillInterceptionResult(observation, activated,
                skillId, skillDisplayName, activated ? "AUTO" : null,
                boundToolIds, refreshedToolsJson);
    }

    /**
     * 脚本工具名列表（skill_{skillId}_{scriptName}，事件载荷用，CR-001）
     */
    private List<String> scriptToolNames(String skillId, List<com.agentdemo.skill.entity.SkillScript> scripts) {
        if (scripts == null || scripts.isEmpty()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (var script : scripts) {
            if (script != null && script.getName() != null && !script.getName().isBlank()) {
                names.add(SkillScriptToolRegistrar.buildToolName(skillId, script.getName()));
            }
        }
        return names;
    }

    /**
     * 解析技能 id（loadSkill 参数可为 id 或名称，经 SkillSessionManager 反查实际 id）
     */
    private String resolveSkillId(String skillName) {
        return skillSessionManager.resolveSkillId(skillName);
    }

    /**
     * 热刷新工具集 JSON（激活后合并绑定工具）
     *
     * @return 新 toolsJson；无变化时返回 null（调用方不刷新）
     */
    private String refreshToolsJson(String sessionId, String currentToolsJson) {
        try {
            // 重解析会话工具（resolveSessionTools 内部已合并激活技能绑定工具，AC-T02）
            List<Object> tools = sessionToolResolver.resolveSessionTools(sessionId, null);
            tools = sessionToolResolver.ensureAskUserTool(tools);
            String refreshed = toolSchemaConverter.convertToJson(tools);
            if (refreshed.equals(currentToolsJson)) {
                return null; // 无变化（如技能无绑定工具），不刷新省开销
            }
            return refreshed;
        } catch (Exception e) {
            log.warn("技能热刷新工具集失败（保持原工具集）: sessionId={}, 原因={}", sessionId, e.getMessage());
            return null;
        }
    }
}
