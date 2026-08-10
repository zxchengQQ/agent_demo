package com.agentdemo.agent.prompt;

import com.agentdemo.agent.config.AgentConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 提示词模板加载器
 * <p>
 * 业务含义：从 classpath 加载角色模板和场景模板，组合为最终系统提示词。
 * 模板文件位于 resources/prompts/roles/ 和 resources/prompts/scenarios/ 目录。
 * 模板缺失时回退到 AgentConfig 默认值，保证系统可用性。
 * </p>
 */
@Slf4j
@Component
public class PromptTemplateLoader {

    public static final String DEFAULT_ROLE = "general";

    // 场景名称常量（对应 prompts/scenarios/ 目录下的文件名）
    public static final String SCENARIO_CHAT = "chat";
    public static final String SCENARIO_THINKING = "thinking";
    public static final String SCENARIO_REACT = "react";
    public static final String SCENARIO_TASK_PLAN = "task-plan";
    public static final String SCENARIO_TASK_EXECUTE = "task-execute";
    public static final String SCENARIO_TASK_SUMMARY = "task-summary";

    private static final String ROLES_DIR = "prompts/roles/";
    private static final String SCENARIOS_DIR = "prompts/scenarios/";

    private final AgentConfig agentConfig;

    public PromptTemplateLoader(AgentConfig agentConfig) {
        this.agentConfig = agentConfig;
    }

    /**
     * 使用配置的默认角色组合提示词
     *
     * @param scenarioName 场景名称（使用 SCENARIO_* 常量）
     * @return 组合后的系统提示词
     */
    public String composeSystemPrompt(String scenarioName) {
        return composeSystemPrompt(agentConfig.getDefaultRole(), scenarioName);
    }

    /**
     * 组合最终系统提示词 = 角色模板 + "\n\n" + 场景模板
     * <p>
     * 回退策略：
     * 1. 角色模板缺失 -> 回退到 general.txt
     * 2. general.txt 也缺失 -> 仅使用场景模板（或 AgentConfig 默认值）
     * 3. 场景模板缺失 -> 回退到 AgentConfig 对应默认值（不拼接角色）
     * </p>
     *
     * @param roleName     角色名称（对应 prompts/roles/ 目录下的文件名，不含扩展名）
     * @param scenarioName 场景名称（使用 SCENARIO_* 常量）
     * @return 组合后的系统提示词
     */
    public String composeSystemPrompt(String roleName, String scenarioName) {
        String roleTemplate = loadRoleTemplate(roleName);
        String scenarioTemplate = loadScenarioTemplate(scenarioName);

        // 场景模板缺失 -> 回退到 AgentConfig（完整提示词，不再拼接角色）
        if (scenarioTemplate == null) {
            log.warn("场景模板 [{}] 不存在，回退到 AgentConfig 默认值", scenarioName);
            return getAgentConfigFallback(scenarioName);
        }

        // 角色模板存在 -> 拼接角色 + 场景
        if (roleTemplate != null) {
            return roleTemplate + "\n\n" + scenarioTemplate;
        }

        // 角色模板缺失，场景模板存在 -> 仅使用场景模板
        log.warn("角色模板 [{}] 及默认角色模板均不存在，仅使用场景模板", roleName);
        return scenarioTemplate;
    }

    /**
     * 加载角色模板，不存在时回退到默认角色
     */
    private String loadRoleTemplate(String roleName) {
        String content = loadTemplate(ROLES_DIR + roleName + ".txt");
        if (content == null && !DEFAULT_ROLE.equals(roleName)) {
            log.warn("角色模板 [{}] 不存在，回退到默认角色 [{}]", roleName, DEFAULT_ROLE);
            content = loadTemplate(ROLES_DIR + DEFAULT_ROLE + ".txt");
        }
        return content;
    }

    /**
     * 加载场景模板
     */
    private String loadScenarioTemplate(String scenarioName) {
        return loadTemplate(SCENARIOS_DIR + scenarioName + ".txt");
    }

    /**
     * 场景模板缺失时，回退到 AgentConfig 中的旧版提示词默认值
     */
    private String getAgentConfigFallback(String scenarioName) {
        return switch (scenarioName) {
            case SCENARIO_CHAT -> agentConfig.getDefaultSystemPrompt();
            case SCENARIO_THINKING -> agentConfig.getThinkingSystemPrompt();
            case SCENARIO_REACT -> agentConfig.getThinkingReactSystemPrompt();
            case SCENARIO_TASK_PLAN -> agentConfig.getTaskBreakdownPlanPrompt();
            case SCENARIO_TASK_EXECUTE -> agentConfig.getTaskExecutionSystemPrompt();
            case SCENARIO_TASK_SUMMARY -> agentConfig.getTaskSummaryPrompt();
            default -> agentConfig.getDefaultSystemPrompt();
        };
    }

    /**
     * 从 classpath 加载模板文件
     *
     * @param path classpath 相对路径，如 "prompts/roles/general.txt"
     * @return 文件内容字符串，文件不存在时返回 null
     */
    private String loadTemplate(String path) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                return null;
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("加载模板文件失败: {}", path, e);
            return null;
        }
    }
}
