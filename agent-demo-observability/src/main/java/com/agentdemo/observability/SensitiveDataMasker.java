package com.agentdemo.observability;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 敏感数据脱敏器（langsmith-observability，Task-05）
 * <p>
 * 业务含义：LangSmith 上报数据出境前的唯一脱敏出口（技术方案 §6.2、决策 6）。
 * 规则（对齐需求 6.3 基础脱敏）：①密钥模式正则 ②已配置环境密钥值精确替换 ③超长截断。
 * 反例保护：sk-/ignore 等正常文本不误伤（正则长度下限约束）；环境值长度过短不参与替换。
 * </p>
 * <p>
 * 保守策略（需求 6.4 降级表）：{@link #maskSafe} 对任何输入不抛异常，内部异常时整字段
 * 替换为 {@link #MASKED}（宁误杀不放过，技术方案 §3.3 失败表）。
 * </p>
 */
public class SensitiveDataMasker {

    /** 密钥模式命中后的替换占位符 */
    public static final String REDACTED = "[REDACTED]";

    /** 保守策略整字段占位符（masker 自身异常时） */
    public static final String MASKED = "[MASKED]";

    /** 超长截断标识 */
    public static final String TRUNCATED = "[TRUNCATED]";

    /** 环境密钥值参与精确替换的最小长度（防误伤过短常见值） */
    private static final int MIN_SECRET_LENGTH = 8;

    /** OpenAI 风格密钥：sk- 前缀 + 16 位以上字符 */
    private static final Pattern SK_KEY_PATTERN = Pattern.compile("sk-[A-Za-z0-9_-]{16,}");

    /** Bearer 令牌：Bearer + 空白 + 16 位以上令牌字符 */
    private static final Pattern BEARER_PATTERN = Pattern.compile("Bearer\\s+[A-Za-z0-9._~+/=-]{16,}");

    private final int maxFieldChars;
    private final List<String> envSecrets;

    /**
     * 便捷构造：无环境密钥值（仅密钥模式正则 + 截断）
     *
     * @param maxFieldChars 单字段上报上限（字符）
     */
    public SensitiveDataMasker(int maxFieldChars) {
        this(maxFieldChars, List.of());
    }

    /**
     * @param maxFieldChars 单字段上报上限（字符），超过截断（AC-E02）
     * @param envSecrets    已配置环境密钥值（非空且长度 >= 8 的参与精确替换）
     */
    public SensitiveDataMasker(int maxFieldChars, Collection<String> envSecrets) {
        this.maxFieldChars = maxFieldChars;
        this.envSecrets = new ArrayList<>();
        if (envSecrets != null) {
            for (String s : envSecrets) {
                if (s != null && !s.isBlank() && s.length() >= MIN_SECRET_LENGTH) {
                    this.envSecrets.add(s);
                }
            }
        }
    }

    /**
     * 脱敏（可返回 null 的安全版本）：内部任何异常降级为整字段 {@link #MASKED}
     *
     * @param value 原始字段值
     * @return 脱敏后字段值；输入 null 返回 null；异常时返回 {@link #MASKED}
     */
    public String maskSafe(String value) {
        try {
            return mask(value);
        } catch (Exception e) {
            // 业务含义：保守策略——宁误杀不放过，整字段替换（技术方案 §3.3 失败表）
            return MASKED;
        }
    }

    /**
     * 脱敏核心逻辑：密钥正则替换 -> 环境密钥值精确替换 -> 超长截断
     *
     * @param value 原始字段值
     * @return 脱敏后字段值；输入 null 返回 null
     */
    public String mask(String value) {
        if (value == null) {
            return null;
        }
        String out = SK_KEY_PATTERN.matcher(value).replaceAll(REDACTED);
        out = BEARER_PATTERN.matcher(out).replaceAll(REDACTED);
        for (String secret : envSecrets) {
            if (out.contains(secret)) {
                out = out.replace(secret, REDACTED);
            }
        }
        if (out.length() > maxFieldChars) {
            out = out.substring(0, maxFieldChars) + TRUNCATED;
        }
        return out;
    }
}
