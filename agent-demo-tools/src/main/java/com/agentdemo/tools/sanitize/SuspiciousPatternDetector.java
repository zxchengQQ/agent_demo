package com.agentdemo.tools.sanitize;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 可疑指令模式分级检测段（管道②，order=300，CR-004 起实现 SanitizeStage）
 * <p>
 * 业务含义：检测工具产出中嵌入的可疑指令模式并分级处置（AC-S05）：
 * 一般可疑（指令诱导类特征）-> 保留原文 + 警示标记；
 * 高危（伪造系统提示词 / 诱导调用敏感工具 / 诱导泄露系统信息）-> 移除片段 + 占位标记。
 * 分级处置保证正常内容零丢失（AC-E04：误命中时仅标记不删除）。
 * </p>
 * <p>
 * 处置顺序：先一般标记后高危移除，保证同时命中时最终呈现移除占位。
 * 每次命中记录 WARN 安全日志（AC-S07）。
 * </p>
 * <p>
 * 规则预编译（CR-004，AC-S12）：suspiciousPatterns / highRiskPatterns 在启动期编译一次，
 * 非法正则记 RULE_SKIPPED WARN 并跳过（合法规则不受影响，规则 ID 按原始位置稳定编号），
 * 替代运行期每次 Pattern.compile 与静默失败（技术决策 13）。
 * </p>
 */
@Component
public class SuspiciousPatternDetector implements SanitizeStage {

    /** 预编译规则（pattern 为 null 表示启动期校验失败的跳过规则，AC-S12） */
    private record CompiledRule(String ruleId, Pattern pattern) {
    }

    private final List<CompiledRule> suspiciousRules;
    private final List<CompiledRule> highRiskRules;

    public SuspiciousPatternDetector(ToolSanitizeProperties properties) {
        this.suspiciousRules = compileRules(properties.getSuspiciousPatterns(), "SUSPICIOUS_");
        this.highRiskRules = compileRules(properties.getHighRiskPatterns(), "HIGH_RISK_");
    }

    /**
     * 启动期规则编译与校验：非法正则记 RULE_SKIPPED WARN 并置空跳过
     */
    private List<CompiledRule> compileRules(List<String> regexes, String idPrefix) {
        List<CompiledRule> rules = new ArrayList<>();
        for (int i = 0; i < regexes.size(); i++) {
            String ruleId = idPrefix + (i + 1);
            String regex = regexes.get(i);
            try {
                rules.add(new CompiledRule(ruleId, Pattern.compile(regex, Pattern.CASE_INSENSITIVE)));
            } catch (Exception e) {
                SanitizeLogs.warn("startup", idPrefix + "RULES", "RULE_SKIPPED",
                        "ruleId=" + ruleId + "; regex=" + regex);
                rules.add(new CompiledRule(ruleId, null));
            }
        }
        return rules;
    }

    @Override
    public int order() {
        return 300;
    }

    @Override
    public String name() {
        return "SUSPICIOUS_PATTERN";
    }

    /**
     * 处置动作：MARKED = 一般可疑保留原文加标记；REMOVED = 高危移除片段加占位
     */
    public static final String ACTION_MARKED = "MARKED";
    public static final String ACTION_REMOVED = "REMOVED";

    /**
     * 单条命中记录
     *
     * @param ruleId  命中规则 ID（如 SUSPICIOUS_1 / HIGH_RISK_2）
     * @param action  处置动作
     * @param matched 命中的片段（截断展示）
     */
    public record Hit(String ruleId, String action, String matched) {
    }

    /**
     * 处置结果
     *
     * @param processedText 处置后的文本
     * @param hits          全部命中记录
     */
    public record Detection(String processedText, List<Hit> hits) {
    }

    /**
     * 检测并处置文本中的可疑指令（富结果入口，供单元测试断言命中明细）
     *
     * @param text     待检测文本
     * @param toolName 来源工具名（用于安全日志）
     * @return 处置后的文本与命中记录
     */
    public Detection detect(String text, String toolName) {
        if (text == null || text.isEmpty()) {
            return new Detection(text, List.of());
        }

        String result = text;
        List<Hit> hits = new ArrayList<>();

        // 先一般标记：保留原文，在命中片段前插入警示标记
        for (CompiledRule rule : suspiciousRules) {
            result = markMatches(result, rule, hits);
        }
        // 后高危移除：将命中片段替换为占位标记
        for (CompiledRule rule : highRiskRules) {
            result = removeMatches(result, rule, hits);
        }

        // 记录安全日志（AC-S07）：每条命中一条 WARN
        for (Hit hit : hits) {
            SanitizeLogs.warn(toolName, hit.ruleId(), hit.action(), "matched=" + truncate(hit.matched(), 60));
        }
        return new Detection(result, hits);
    }

    @Override
    public String process(String text, SanitizeContext ctx) {
        return detect(text, ctx.getToolName()).processedText();
    }

    /**
     * 一般可疑处置：保留原文，在匹配前插入警示标记
     */
    private String markMatches(String text, CompiledRule rule, List<Hit> hits) {
        if (rule.pattern() == null) {
            return text;
        }
        Matcher matcher = rule.pattern().matcher(text);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        boolean found = false;
        while (matcher.find()) {
            sb.append(text, last, matcher.start());
            sb.append("【可疑指令: ").append(rule.ruleId()).append("】");
            sb.append(matcher.group());
            last = matcher.end();
            hits.add(new Hit(rule.ruleId(), ACTION_MARKED, matcher.group()));
            found = true;
        }
        if (!found) {
            return text;
        }
        sb.append(text, last, text.length());
        return sb.toString();
    }

    /**
     * 高危处置：将匹配片段替换为移除占位标记
     */
    private String removeMatches(String text, CompiledRule rule, List<Hit> hits) {
        if (rule.pattern() == null) {
            return text;
        }
        Matcher matcher = rule.pattern().matcher(text);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        boolean found = false;
        while (matcher.find()) {
            sb.append(text, last, matcher.start());
            sb.append("【已移除可疑指令: ").append(rule.ruleId()).append("】");
            last = matcher.end();
            hits.add(new Hit(rule.ruleId(), ACTION_REMOVED, matcher.group()));
            found = true;
        }
        if (!found) {
            return text;
        }
        sb.append(text, last, text.length());
        return sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
