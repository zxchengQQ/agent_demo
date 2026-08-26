package com.agentdemo.tools.sanitize;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 清洗动作安全日志统一格式
 * <p>
 * 业务含义：统一所有清洗动作（HTML 剥离/模式标记/高危移除/截断/临时文件）的 WARN 日志格式，
 * 便于审计追溯（AC-S07）。日志仅含元信息（工具名/规则/动作/明细），不含工具结果正文，
 * 避免敏感内容二次落盘。
 * </p>
 */
public final class SanitizeLogs {

    private static final Logger log = LoggerFactory.getLogger(SanitizeLogs.class);

    private SanitizeLogs() {
    }

    /**
     * 记录清洗动作 WARN 日志
     *
     * @param toolName 来源工具名
     * @param ruleId   命中规则 ID 或动作标识
     * @param action   处置动作（HTML_STRIPPED / MARKED / REMOVED / TRUNCATED / TEMP_FILE）
     * @param detail   明细（如长度统计、临时文件路径），可为空
     */
    public static void warn(String toolName, String ruleId, String action, String detail) {
        log.warn("[tool-sanitize] toolName={}, ruleId={}, action={}, detail={}",
                toolName, ruleId, action, detail);
    }
}
