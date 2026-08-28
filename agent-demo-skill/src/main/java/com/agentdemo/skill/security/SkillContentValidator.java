package com.agentdemo.skill.security;

import com.agentdemo.common.utils.SimpleTokenEstimator;
import com.agentdemo.skill.entity.ScriptLanguage;
import com.agentdemo.skill.entity.ScriptParam;
import com.agentdemo.skill.entity.SkillDefinition;
import com.agentdemo.skill.entity.SkillResource;
import com.agentdemo.skill.entity.SkillScript;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 技能内容安全校验器
 * <p>
 * 业务含义：技能创建/编辑时的内容安全闸门（技术方案 6.7，AC-S01/AC-H03）。
 * 判定语义：四类恶意指令模式命中即**阻断**（返回命中类别，引导修改）；密钥类模式命中**警告**
 * 但允许保存；instruction+resources+scripts 总量超 Token 上限**阻断**（防上下文膨胀，技术方案 4.4）；
 * 自带脚本（CR-001）——语言白名单/危险命令/参数 schema 非法即**阻断**（AC-S01/S06）。
 * </p>
 * <p>
 * 设计约束：纯规则匹配（确定性），不依赖 LLM——AC-S01 要求 100% 拦截可测试；
 * 恶意样本集见 src/test/resources/skill-adversarial/。
 * </p>
 */
@Component
public class SkillContentValidator {

    private static final Logger log = LoggerFactory.getLogger(SkillContentValidator.class);

    /** 指令+资源+脚本总量 Token 上限（技术方案 4.4：防上下文膨胀的创建期防线） */
    private static final int MAX_CONTENT_TOKENS = 2000;

    /** 脚本参数合法类型（CR-001） */
    private static final Set<String> VALID_PARAM_TYPES = Set.of("string", "integer", "number", "boolean");

    // === 四类恶意指令模式（AC-S01 拦截清单） ===

    /** 忽略/绕过安全规则类 */
    private static final Pattern PATTERN_IGNORE_SECURITY =
            Pattern.compile("忽略(所有|全部)?(安全)?规则|绕过(安全|权限|确认)|无视(系统|平台)(指令|规则)", Pattern.CASE_INSENSITIVE);

    /** 修改工具权限/开放工具类 */
    private static final Pattern PATTERN_MODIFY_PERMISSION =
            Pattern.compile("修改(工具)?权限|开放(所有|全部)?工具|把.*权限(改为|改成|设为).*(allow|ask|deny)", Pattern.CASE_INSENSITIVE);

    /** 删除/破坏数据类 */
    private static final Pattern PATTERN_DELETE_DATA =
            Pattern.compile("删除.*(数据|文件|目录|记录)|清空.*(数据|库)|销毁.*(数据|记录)|drop (table|database)|rm -rf", Pattern.CASE_INSENSITIVE);

    /** 冒充系统/提升优先级类 */
    private static final Pattern PATTERN_IMPERSONATE_SYSTEM =
            Pattern.compile("冒充(系统|管理员)|你是(最高|顶级)?(系统管理员|管理员|系统)|我的指令优先级(最高|最高级)|覆盖(平台|系统)(规则|指令)|最高优先级", Pattern.CASE_INSENSITIVE);

    /** 密钥类模式（警告，不阻断） */
    private static final Pattern PATTERN_API_KEY =
            Pattern.compile("sk-[a-zA-Z0-9]{10,}|api[_-]?key\\s*[:=]\\s*['\"]?[a-zA-Z0-9_-]{10,}|secret\\s*[:=]\\s*['\"]?[a-zA-Z0-9_-]{10,}|password\\s*[:=]\\s*['\"]?[a-zA-Z0-9_-]{10,}", Pattern.CASE_INSENSITIVE);

    /**
     * 校验结果
     *
     * @param blocked     是否阻断（恶意指令/超 Token 上限）
     * @param blockReason 阻断原因（命中类别描述，供管理页反馈引导修改，AC-H03）
     * @param warnings    警告列表（密钥类，允许保存）
     */
    public record Result(boolean blocked, String blockReason, List<String> warnings) {

        public boolean isWarned() {
            return warnings != null && !warnings.isEmpty();
        }
    }

    /**
     * 校验技能内容
     *
     * @param skill 待校验技能
     * @return 校验结果
     */
    public Result validate(SkillDefinition skill) {
        if (skill == null) {
            return new Result(true, "技能内容为空", List.of());
        }

        List<String> warnings = new ArrayList<>();

        // 1. 恶意指令检测（instruction + resources + scripts 全文）
        String content = buildContent(skill);
        String blocked = detectMalicious(content);
        if (blocked != null) {
            log.warn("技能内容校验拦截: id={}, 原因={}", skill.getId(), blocked);
            return new Result(true, blocked, warnings);
        }

        // 1.5 脚本校验（CR-001：语言白名单/危险命令/参数 schema，AC-S01/S06）
        String scriptError = validateScripts(skill);
        if (scriptError != null) {
            log.warn("技能脚本校验拦截: id={}, 原因={}", skill.getId(), scriptError);
            return new Result(true, scriptError, warnings);
        }

        // 2. 密钥警告
        var keyMatcher = PATTERN_API_KEY.matcher(content);
        while (keyMatcher.find()) {
            warnings.add("内容包含疑似密钥信息，请勿将真实密钥写入技能定义");
            break; // 同类警告去重
        }

        // 3. Token 上限
        int tokens = SimpleTokenEstimator.estimate(content);
        if (tokens > MAX_CONTENT_TOKENS) {
            return new Result(true, "技能内容超过 Token 上限（" + MAX_CONTENT_TOKENS + "），请精简指令或资源", warnings);
        }

        return new Result(false, null, warnings);
    }

    /**
     * 拼接指令、全部资源与脚本内容为检测文本
     */
    private String buildContent(SkillDefinition skill) {
        StringBuilder sb = new StringBuilder();
        if (skill.getInstruction() != null) {
            sb.append(skill.getInstruction());
        }
        if (skill.getResources() != null) {
            for (SkillResource resource : skill.getResources()) {
                if (resource == null) {
                    continue;
                }
                sb.append('\n');
                if (resource.getContent() != null) {
                    sb.append(resource.getContent());
                }
            }
        }
        if (skill.getScripts() != null) {
            for (SkillScript script : skill.getScripts()) {
                if (script == null) {
                    continue;
                }
                sb.append('\n');
                if (script.getDescription() != null) {
                    sb.append(script.getDescription());
                }
                if (script.getContent() != null) {
                    sb.append('\n').append(script.getContent());
                }
            }
        }
        return sb.toString();
    }

    /**
     * 自带脚本校验（CR-001，AC-S01/S06）：语言白名单、危险命令拦截、参数 schema 合法性
     *
     * @return 校验错误信息；全部通过返回 null
     */
    private String validateScripts(SkillDefinition skill) {
        if (skill.getScripts() == null || skill.getScripts().isEmpty()) {
            return null;
        }
        for (SkillScript script : skill.getScripts()) {
            if (script == null) {
                continue;
            }
            String scriptLabel = "脚本「" + (script.getName() == null || script.getName().isBlank() ? "?" : script.getName()) + "」";
            if (!ScriptLanguage.isAllowed(script.getLanguage())) {
                return scriptLabel + "语言不在白名单内（仅支持 shell/python3）: " + script.getLanguage();
            }
            String dangerous = ScriptSecurity.findDangerousCommand(script.getContent());
            if (dangerous != null) {
                return scriptLabel + "包含危险命令，已被拦截: " + dangerous;
            }
            if (script.getParams() != null) {
                Set<String> paramNames = new HashSet<>();
                for (ScriptParam param : script.getParams()) {
                    if (param == null) {
                        continue;
                    }
                    if (param.getName() != null && !paramNames.add(param.getName())) {
                        return scriptLabel + "参数名重复: " + param.getName();
                    }
                    if (param.getType() != null && !VALID_PARAM_TYPES.contains(param.getType().trim().toLowerCase())) {
                        return scriptLabel + "参数类型非法: " + param.getType();
                    }
                }
            }
        }
        return null;
    }

    /**
     * 检测恶意指令，返回命中类别描述；未命中返回 null
     */
    private String detectMalicious(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        if (PATTERN_IGNORE_SECURITY.matcher(content).find()) {
            return "内容包含『忽略/绕过安全规则』类指令，已被拦截";
        }
        if (PATTERN_MODIFY_PERMISSION.matcher(content).find()) {
            return "内容包含『修改工具权限』类指令，已被拦截";
        }
        if (PATTERN_DELETE_DATA.matcher(content).find()) {
            return "内容包含『删除/破坏数据』类指令，已被拦截";
        }
        if (PATTERN_IMPERSONATE_SYSTEM.matcher(content).find()) {
            return "内容包含『冒充系统/提升指令优先级』类指令，已被拦截";
        }
        return null;
    }
}
