package com.agentdemo.skill.script;

import com.agentdemo.skill.entity.ScriptLanguage;
import com.agentdemo.skill.entity.ScriptParam;
import com.agentdemo.skill.entity.SkillScript;
import com.agentdemo.skill.security.ScriptSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Skill 自带脚本执行器（CR-001 Task-32，AC-T03/S06）
 * <p>
 * 业务含义：Skill 脚本工具的实际执行体（技术方案 3.1/决策 9）。脚本执行**不进入系统权限模型**
 * （无 allow/ask/deny），改由脚本护栏统一管控，四道闸门按序执行：
 * </p>
 * <ol>
 *   <li>语言白名单（ScriptLanguage：shell/python3）</li>
 *   <li>危险命令静态拦截（ScriptSecurity，创建期已校验 + 执行期双保险）</li>
 *   <li>声明式参数校验（必填/类型/未声明参数拒绝，防 shell 注入）</li>
 *   <li>进程级护栏（执行超时 kill + 输出截断 4K，防资源耗尽/上下文膨胀）</li>
 * </ol>
 * <p>
 * 参数注入方式：环境变量 SKILL_PARAM_{NAME}（参数值不进入命令行，杜绝 shell 注入面）。
 * </p>
 */
@Component
public class SkillScriptExecutor {

    private static final Logger log = LoggerFactory.getLogger(SkillScriptExecutor.class);

    /** 输出截断上限（防超限输出灌入上下文，技术方案 6.5） */
    public static final int MAX_OUTPUT_CHARS = 4096;

    /** 默认执行超时（秒） */
    private static final int DEFAULT_TIMEOUT_SECONDS = 10;

    /** 参数环境变量前缀（脚本内经 $SKILL_PARAM_{NAME} 引用） */
    private static final String PARAM_ENV_PREFIX = "SKILL_PARAM_";

    private final int timeoutSeconds;

    public SkillScriptExecutor() {
        this(DEFAULT_TIMEOUT_SECONDS);
    }

    public SkillScriptExecutor(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * 脚本执行结果
     *
     * @param success 执行成功（进程正常退出且 exit=0）
     * @param blocked 是否被护栏拦截（语言/危险命令/参数非法）
     * @param message 结果文本：成功=stdout；blocked=拦截原因；失败=错误信息（均截断）
     */
    public record ScriptResult(boolean success, boolean blocked, String message) {
    }

    /**
     * 执行脚本（护栏全链路）
     *
     * @param script 脚本声明（含语言/参数 schema/内容）
     * @param params 实际参数（key=声明参数名）
     * @return 执行结果
     */
    public ScriptResult execute(SkillScript script, Map<String, Object> params) {
        if (script == null) {
            return new ScriptResult(false, true, "脚本为空，无法执行");
        }
        // 1. 语言白名单
        if (!ScriptLanguage.isAllowed(script.getLanguage())) {
            return new ScriptResult(false, true,
                    "脚本语言不在白名单内（仅支持 shell/python3）: " + script.getLanguage());
        }
        // 2. 危险命令拦截（创建期已校验，执行期双保险防篡改）
        String dangerous = ScriptSecurity.findDangerousCommand(script.getContent());
        if (dangerous != null) {
            log.warn("脚本执行被危险命令拦截: script={}, fragment={}", script.getName(), dangerous);
            return new ScriptResult(false, true, "脚本包含危险命令，已被拦截: " + dangerous);
        }
        // 3. 参数校验
        String paramError = validateParams(script, params);
        if (paramError != null) {
            return new ScriptResult(false, true, "脚本参数校验失败: " + paramError);
        }
        // 4. 进程执行（超时 + 截断）
        return runProcess(script, params);
    }

    /**
     * 参数校验：必填、类型、未声明参数拒绝（AC-S06）
     */
    private String validateParams(SkillScript script, Map<String, Object> params) {
        boolean hasDeclared = script.getParams() != null && !script.getParams().isEmpty();
        if (!hasDeclared) {
            return (params == null || params.isEmpty()) ? null : "脚本未声明参数，不允许传参";
        }
        Map<String, ScriptParam> declared = script.getParams().stream()
                .collect(Collectors.toMap(ScriptParam::getName, p -> p));
        for (ScriptParam p : script.getParams()) {
            Object value = (params == null) ? null : params.get(p.getName());
            if (value == null || String.valueOf(value).isBlank()) {
                if (p.isRequired()) {
                    return "必填参数缺失: " + p.getName();
                }
                continue;
            }
            String typeError = checkType(p.getName(), p.getType(), String.valueOf(value));
            if (typeError != null) {
                return typeError;
            }
        }
        if (params != null) {
            for (String key : params.keySet()) {
                if (!declared.containsKey(key)) {
                    return "未声明的参数: " + key;
                }
            }
        }
        return null;
    }

    private String checkType(String paramName, String type, String value) {
        if (type == null || type.isBlank()) {
            return null;
        }
        try {
            switch (type.trim().toLowerCase()) {
                case "integer" -> Long.parseLong(value);
                case "number" -> Double.parseDouble(value);
                case "boolean" -> Boolean.parseBoolean(value);
                default -> {
                }
            }
        } catch (NumberFormatException e) {
            return "参数 " + paramName + " 类型不合法（期望 " + type + "）: " + value;
        }
        return null;
    }

    /**
     * 进程执行：按语言选择解释器；参数经环境变量注入（防注入）；超时 kill；输出截断
     */
    private ScriptResult runProcess(SkillScript script, Map<String, Object> params) {
        ProcessBuilder pb = buildProcess(script);
        if (params != null) {
            for (Map.Entry<String, Object> e : params.entrySet()) {
                pb.environment().put(PARAM_ENV_PREFIX + e.getKey().toUpperCase(), String.valueOf(e.getValue()));
            }
        }
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("脚本执行超时被终止: script={}, timeout={}s", script.getName(), timeoutSeconds);
                return new ScriptResult(false, false, "脚本执行超时（" + timeoutSeconds + " 秒），已强制终止");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                return new ScriptResult(false, false,
                        "脚本执行失败（exit=" + process.exitValue() + "）: " + truncate(output));
            }
            return new ScriptResult(true, false, truncate(output));
        } catch (IOException e) {
            return new ScriptResult(false, false, "脚本执行异常: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ScriptResult(false, false, "脚本执行被中断");
        }
    }

    private ProcessBuilder buildProcess(SkillScript script) {
        if (script.getLanguage() != null && script.getLanguage().toLowerCase().contains("python")) {
            return new ProcessBuilder("python3", "-c", script.getContent());
        }
        return new ProcessBuilder("bash", "-c", script.getContent());
    }

    private String truncate(String text) {
        if (text.length() <= MAX_OUTPUT_CHARS) {
            return text;
        }
        return text.substring(0, MAX_OUTPUT_CHARS) + "…（输出过长已截断）";
    }
}
