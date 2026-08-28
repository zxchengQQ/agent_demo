package com.agentdemo.agent.single;

import java.util.List;

/**
 * 技能工具拦截器（agent-skill 决策 4：流式路径 loadSkill 拦截）
 * <p>
 * 业务含义：HITLReActStream 拦截 loadSkill 工具调用时的协作接口。由宿主
 * （UnifiedChatStream/TaskBreakdownStream）提供实现，组合 SkillLoadTool（激活逻辑+观察值）、
 * SkillSessionManager（激活态）、SessionToolResolver+ToolSchemaConverter（工具热刷新）。
 * </p>
 * <p>
 * 设计约束：接口保持单方法、宿主零循环依赖；HITLReActStream 仅依赖此接口，
 * 不直接依赖 skill 模块类型（app 模块构造点不注入时走无拦截路径）。
 * </p>
 */
public interface SkillToolInterceptor {

    /**
     * 处理 loadSkill 工具调用（激活 + 热刷新工具集）
     *
     * @param sessionId      会话 ID
     * @param skillName      loadSkill 参数（技能 id 或名称）
     * @param currentToolsJson 当前 toolsJson（激活前）
     * @param iteration      当前 ReAct 迭代
     * @return 拦截处理结果（观察值 + 是否激活成功 + 事件载荷 + 热刷新后的 toolsJson）
     */
    SkillInterceptionResult interceptLoadSkill(String sessionId, String skillName,
                                               String currentToolsJson, int iteration);

    /**
     * 拦截处理结果
     *
     * @param observation      观察值（回填 ToolExecutionResultMessage）
     * @param activated        是否激活成功（true 才触发 skill_activated 事件与热刷新）
     * @param skillId          技能 id（激活成功时有效）
     * @param skillName        技能名称（激活成功时有效）
     * @param source           激活来源（AUTO/MANUAL，激活成功时有效）
     * @param boundToolIds     绑定工具 id（激活成功时有效，事件载荷用）
     * @param refreshedToolsJson 热刷新后的 toolsJson（有新增绑定工具时非 null，否则 null 表示无需刷新）
     */
    record SkillInterceptionResult(String observation, boolean activated,
                                   String skillId, String skillName, String source,
                                   List<String> boundToolIds, String refreshedToolsJson) {

        public static SkillInterceptionResult noIntercept(String observation) {
            return new SkillInterceptionResult(observation, false, null, null, null, List.of(), null);
        }
    }
}
