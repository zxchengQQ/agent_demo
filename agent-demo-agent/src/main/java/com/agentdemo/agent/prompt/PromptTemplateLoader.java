package com.agentdemo.agent.prompt;

import com.agentdemo.agent.config.AgentConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    public static final String SCENARIO_HITL = "hitl";

    /** 场景名：HITL 工具引导段（工作流 HITL 路径三段组合的第三段，含 {{tools}} 占位，Task-14） */
    public static final String SCENARIO_HITL_GUIDANCE = "hitl-guidance";

    private static final String ROLES_DIR = "prompts/roles/";
    private static final String SCENARIOS_DIR = "prompts/scenarios/";
    private static final String FRAGMENTS_DIR = "prompts/fragments/";

    /** include 占位符：{{include:fragment-name}}，单层展开（不递归嵌套） */
    private static final Pattern INCLUDE_PATTERN = Pattern.compile("\\{\\{include:([\\w-]+)}}");

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
     * 加载场景模板（公开单段加载，供 AgentExecutor 加载 hitl-guidance 引导段，Task-14）
     *
     * @param scenarioName 场景名称（使用 SCENARIO_* 常量）
     * @return 场景模板内容，文件不存在时返回 null（调用方需处理降级）
     */
    public String loadScenarioTemplate(String scenarioName) {
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
            case SCENARIO_HITL -> agentConfig.getThinkingReactSystemPrompt();
            default -> agentConfig.getDefaultSystemPrompt();
        };
    }

    /**
     * 从 classpath 加载模板文件，并对内容执行单层片段展开（CR-001 Task-19）
     *
     * @param path classpath 相对路径，如 "prompts/roles/general.txt"
     * @return 文件内容字符串（含 include 展开），文件不存在时返回 null
     */
    private String loadTemplate(String path) {
        String raw = loadRawTemplate(path);
        return expandFragments(raw);
    }

    /**
     * 纯读取模板文件内容（不做片段展开）
     */
    private String loadRawTemplate(String path) {
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

    /**
     * 单层片段展开：{{include:fragment-name}} 替换为 prompts/fragments/{fragment-name}.txt 内容。
     * 片段缺失时 WARN 并原样保留占位符（降级不中断）；片段内容不递归展开（单层语义，防循环引用）。
     */
    private String expandFragments(String template) {
        if (template == null || !template.contains("{{include:")) {
            return template;
        }
        Matcher matcher = INCLUDE_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String fragmentName = matcher.group(1);
            String fragment = loadRawTemplate(FRAGMENTS_DIR + fragmentName + ".txt");
            if (fragment == null) {
                log.warn("片段 [{}] 不存在，保留 include 占位符（降级不中断）", fragmentName);
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group()));
            } else {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(fragment));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
