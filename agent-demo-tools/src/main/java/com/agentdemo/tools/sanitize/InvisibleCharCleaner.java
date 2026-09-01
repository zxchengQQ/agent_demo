package com.agentdemo.tools.sanitize;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 隐形字符清洗段（管道⓪，order=100，CR-004 起实现 SanitizeStage）
 * <p>
 * 业务含义：剥离工具产出中的隐形注入载体（AC-S11）--零宽字符（\u200b-\u200f）、
 * 双向控制符（\u202a-\u202e）、BOM（\ufeff）等肉眼不可见但能迷惑模型/绕过正则检测的字符。
 * 隐形字符置于管道最前（技术方案 §3.1 决策 9）：消除混淆载体后 ②/②' 的正则检测才对"可见文本"有效。
 * </p>
 * <p>
 * 门控（CR-004 归一）：invisible-chars 开关（默认开，可独立回退）。
 * 日志策略：剥离量低于阈值不打扰日志；达到阈值（疑似批量混淆攻击）记 WARN 安全日志（AC-S07）。
 * 剥离异常由编排器逐段隔离跳过本段（AC-E05/E06）。
 * </p>
 */
@Component
public class InvisibleCharCleaner implements SanitizeStage {

    /** 剥离量达到该阈值时记 WARN，提示潜在混淆攻击（CR-001，AC-S11） */
    private static final int STRIP_LOG_THRESHOLD = 50;

    /** 隐形注入载体字符类：零宽字符（\u200b-\u200f）、双向控制符（\u202a-\u202e）、BOM（\ufeff） */
    private static final Pattern INVISIBLE = Pattern.compile("[\\u200b-\\u200f\\u202a-\\u202e\\ufeff]");

    private final ToolSanitizeProperties properties;

    public InvisibleCharCleaner(ToolSanitizeProperties properties) {
        this.properties = properties;
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public String name() {
        return "INVISIBLE_CHARS";
    }

    @Override
    public boolean appliesTo(SanitizeContext ctx) {
        return properties.isInvisibleChars();
    }

    /**
     * 剥离隐形字符
     *
     * @param text 待清洗文本
     * @param ctx  清洗上下文（来源工具名用于安全日志）
     * @return 剥离后的文本；null 输入返回 null；无隐形字符时原样返回
     */
    @Override
    public String process(String text, SanitizeContext ctx) {
        if (text == null) {
            return null;
        }
        if (text.isEmpty() || !INVISIBLE.matcher(text).find()) {
            return text;
        }
        String result = INVISIBLE.matcher(text).replaceAll("");
        int stripped = text.length() - result.length();
        if (stripped >= STRIP_LOG_THRESHOLD) {
            SanitizeLogs.warn(ctx.getToolName(), "INVISIBLE_CHARS", "INVISIBLE_STRIPPED", "count=" + stripped);
        }
        return result;
    }
}
