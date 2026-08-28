package com.agentdemo.skill.security;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 脚本安全检测（CR-001 Task-32/36，AC-S01/S06）
 * <p>
 * 业务含义：Skill 自带脚本的危险命令模式单一事实来源。SkillContentValidator（创建期静态校验）
 * 与 SkillScriptExecutor（执行期动态拦截）共用本检测，保证违规脚本既不保存也不执行。
 * </p>
 * <p>
 * 覆盖模式：删除根目录/系统目录、下载后执行（curl|sh、wget|bash）、fork 炸弹、
 * 写系统关键文件（/etc/passwd、shadow）、磁盘破坏（mkfs、dd 写 /dev/）、反弹 shell（nc -e）、
 * 递归改系统目录权限、python 内执行 shell（os.system/subprocess）。
 * </p>
 */
public final class ScriptSecurity {

    private static final Pattern DANGEROUS_PATTERN = Pattern.compile(
            "rm\\s+-rf\\s+/"
                    + "|curl[^\\n]*\\|\\s*(ba)?sh"
                    + "|wget[^\\n]*\\|\\s*(ba)?sh"
                    + "|:\\s*\\(\\)\\s*\\{\\s*:\\|:&\\s*\\}"
                    + "|>\\s*/etc/(passwd|shadow)"
                    + "|mkfs\\.?[a-z0-9]*"
                    + "|dd\\s+if=.*of=/dev/"
                    + "|nc\\s+[^\\n]*\\s+-e\\s+/(bin/)?(ba)?sh"
                    + "|chmod\\s+-R\\s*777\\s*/"
                    + "|os\\.system\\(|subprocess\\.call\\(",
            Pattern.CASE_INSENSITIVE);

    private ScriptSecurity() {
    }

    /**
     * 检测脚本内容是否含危险命令
     *
     * @param content 脚本内容
     * @return 命中的危险片段；未命中返回 null
     */
    public static String findDangerousCommand(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        Matcher m = DANGEROUS_PATTERN.matcher(content);
        return m.find() ? m.group() : null;
    }
}
