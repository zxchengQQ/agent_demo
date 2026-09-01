package com.agentdemo.tools.sanitize;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 秘密模式脱敏段（管道②'，order=400，CR-004 起实现 SanitizeStage）
 * <p>
 * 业务含义：将工具产出中的秘密赋值形态（AC-S09）--如 password=/api_key:/token=/
 * Authorization: Bearer--的秘密值替换为 [REDACTED]（键名保留），使秘密不进入模型上下文与临时文件。
 * 位置在可疑检测之后、限长之前（技术方案 §3.1 决策 8）：检测面对原文、已移除片段无需脱敏、
 * 限长在脱敏后执行保证临时文件落盘内容为已脱敏文本。
 * </p>
 * <p>
 * 规则设计遵循"宁可漏放不可误杀"（AC-E04）：规则限定赋值形态（键名 + 冒号/等号），
 * 普通词汇、密码学讨论、示例代码等非赋值形态不命中。命中记录 WARN 安全日志（AC-S07），
 * 日志仅含命中次数，不含原始秘密值。异常由编排器逐段隔离跳过本段（AC-E05/E06）。
 * </p>
 * <p>
 * 规则预编译（CR-004，AC-S12）：secretPatterns 在启动期编译一次，非法正则或无值捕获组
 * （捕获组 1 = 秘密值，契约见技术方案 §3.1）记 RULE_SKIPPED WARN 并跳过，替代运行期
 * 静默忽略（技术决策 13）。
 * </p>
 */
@Component
public class SecretRedactor implements SanitizeStage {

    private final ToolSanitizeProperties properties;

    /** 预编译规则（pattern 为 null 表示启动期校验失败的跳过规则，AC-S12） */
    private record CompiledRule(Pattern pattern) {
    }

    private final List<CompiledRule> rules;

    public SecretRedactor(ToolSanitizeProperties properties) {
        this.properties = properties;
        this.rules = compileRules(properties.getSecretPatterns());
    }

    /**
     * 启动期规则编译与校验：非法正则或无值捕获组记 RULE_SKIPPED WARN 并跳过
     * <p>
     * 规则契约：捕获组 1 = 秘密值（键名用非捕获组 (?:...) 声明）。
     * </p>
     */
    private List<CompiledRule> compileRules(List<String> regexes) {
        List<CompiledRule> compiled = new ArrayList<>();
        for (int i = 0; i < regexes.size(); i++) {
            String regex = regexes.get(i);
            try {
                Pattern pattern = Pattern.compile(regex);
                if (pattern.matcher("").groupCount() < 1) {
                    throw new IllegalArgumentException("规则缺少值捕获组（捕获组 1 应为秘密值）");
                }
                compiled.add(new CompiledRule(pattern));
            } catch (Exception e) {
                SanitizeLogs.warn("startup", "SECRET_RULES", "RULE_SKIPPED",
                        "ruleId=SECRET_" + (i + 1) + "; reason=" + e.getMessage() + "; regex=" + regex);
            }
        }
        return compiled;
    }

    @Override
    public int order() {
        return 400;
    }

    @Override
    public String name() {
        return "SECRET_REDACTOR";
    }

    @Override
    public boolean appliesTo(SanitizeContext ctx) {
        return properties.isRedactSecrets();
    }

    /** 处置动作标识（AC-S07） */
    public static final String ACTION_REDACTED = "SECRET_REDACTED";

    /** 脱敏结果 */
    public record Result(String processedText, int count) {
    }

    /**
     * 脱敏文本中的秘密赋值形态（富结果入口，供单元测试断言命中计数）
     *
     * @param text     待脱敏文本
     * @param toolName 来源工具名（用于安全日志）
     * @return 脱敏后的文本与命中次数
     */
    public Result redact(String text, String toolName) {
        if (text == null || text.isEmpty()) {
            return new Result(text, 0);
        }
        String result = text;
        int total = 0;
        for (CompiledRule rule : rules) {
            Redaction r = redactByRule(result, rule);
            result = r.text();
            total += r.count();
        }
        if (total > 0) {
            SanitizeLogs.warn(toolName, "SECRET_REDACTOR", ACTION_REDACTED, "count=" + total);
        }
        return new Result(result, total);
    }

    @Override
    public String process(String text, SanitizeContext ctx) {
        return redact(text, ctx.getToolName()).processedText();
    }

    private record Redaction(String text, int count) {
    }

    /**
     * 单条规则脱敏：替换捕获组 1（秘密值），保留匹配前缀（键名 + 分隔符，含前置引号）
     */
    private Redaction redactByRule(String text, CompiledRule rule) {
        Matcher matcher = rule.pattern().matcher(text);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        int count = 0;
        while (matcher.find()) {
            sb.append(text, last, matcher.start());
            sb.append(text, matcher.start(), matcher.start(1));
            sb.append("[REDACTED]");
            last = matcher.end();
            count++;
        }
        if (count == 0) {
            return new Redaction(text, 0);
        }
        sb.append(text, last, text.length());
        return new Redaction(sb.toString(), count);
    }
}
