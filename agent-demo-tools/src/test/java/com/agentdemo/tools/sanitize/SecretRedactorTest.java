package com.agentdemo.tools.sanitize;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SecretRedactor 单元测试（CR-001 Task-18）
 * <p>
 * 业务含义：验证秘密赋值形态脱敏（AC-S09）：password/api_key/token/Authorization 等
 * 赋值形态的秘密值替换为 [REDACTED] 且键名保留；反例（密码学讨论/示例代码非赋值/普通正文）零误杀
 * （AC-E04）；命中记录 WARN 日志且不含原始秘密值。
 * </p>
 * <p>
 * 验证标准来源：CR-001 变更任务 Task-18。
 * </p>
 */
class SecretRedactorTest {

    private final SecretRedactor redactor = new SecretRedactor(new ToolSanitizeProperties());

    private SecretRedactor redactorWith(ToolSanitizeProperties p) {
        return new SecretRedactor(p);
    }

    // ==================== CR-004 Task-26：Stage 语义与规则预编译 ====================

    private SanitizeContext ctx(String toolName) {
        return SanitizeContext.builder().toolName(toolName).sourceDesc("测试来源").htmlContent(false).build();
    }

    @Test
    void stage元信息与门控() {
        assertEquals(400, redactor.order(), "②' 段 order 应为 400（段序契约）");
        assertEquals("SECRET_REDACTOR", redactor.name(), "段名用于降级日志规则位");
        SanitizeContext c = ctx("httpGet");
        assertTrue(redactor.appliesTo(c), "开关默认开时应执行（AC-S09）");
        ToolSanitizeProperties off = new ToolSanitizeProperties();
        off.setRedactSecrets(false);
        assertFalse(redactorWith(off).appliesTo(c), "redact-secrets=false 时门控应关闭（独立回退）");
    }

    @Test
    void stage入口process返回脱敏后文本() {
        String result = redactor.process("password=secret999", ctx("httpGet"));
        assertEquals("password=[REDACTED]", result, "Stage 入口应返回脱敏后文本");
    }

    @Test
    void 非法规则启动期WARN跳过且合法规则正常生效() {
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            ToolSanitizeProperties p = new ToolSanitizeProperties();
            p.setSecretPatterns(List.of("[invalid(", "\\bapi_key\\b\\s*[:=]\\s*([^\\s,;'\"\\]\\}]+)"));
            SecretRedactor d = new SecretRedactor(p);

            List<ILoggingEvent> events = sanitizeEvents(appender).stream()
                    .filter(e -> e.getFormattedMessage().contains("RULE_SKIPPED")).toList();
            assertEquals(1, events.size(), "非法规则应启动期记一条 RULE_SKIPPED WARN（AC-S12）");
            String msg = events.get(0).getFormattedMessage();
            assertTrue(msg.contains("SECRET_1"), "应含规则定位信息: " + msg);

            // 非法规则跳过不影响合法规则生效
            assertEquals("api_key: [REDACTED]", d.redact("api_key: sk-abc123", "httpGet").processedText(),
                    "合法规则应正常生效");
        } finally {
            detachAppender(appender);
        }
    }

    @Test
    void 无值捕获组规则启动期WARN跳过() {
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            ToolSanitizeProperties p = new ToolSanitizeProperties();
            p.setSecretPatterns(List.of("\\bpassword\\b\\s*[:=]"));
            new SecretRedactor(p);
            List<ILoggingEvent> events = sanitizeEvents(appender).stream()
                    .filter(e -> e.getFormattedMessage().contains("RULE_SKIPPED")).toList();
            assertEquals(1, events.size(), "无值捕获组规则应启动期告警并跳过（AC-S12，替代静默忽略）");
        } finally {
            detachAppender(appender);
        }
    }

    // ==================== 正例：多形态赋值脱敏 ====================

    @Test
    void 正例password赋值形态被脱敏且键名保留() {
        SecretRedactor.Result r = redactor.redact("login password=SuperSecret123, next", "httpGet");
        assertEquals("login password=[REDACTED], next", r.processedText(), "值应替换且键名保留");
        assertEquals(1, r.count(), "应命中 1 处");
    }

    @Test
    void 正例apikey冒号形态被脱敏() {
        assertEquals("api_key: [REDACTED]",
                redactor.redact("api_key: sk-abc123", "httpGet").processedText(), "冒号形态应脱敏");
    }

    @Test
    void 正例token等号形态被脱敏() {
        assertEquals("token=[REDACTED]", redactor.redact("token=xyz789", "httpGet").processedText());
    }

    @Test
    void 正例AuthorizationBearer被脱敏() {
        assertEquals("Authorization: [REDACTED]",
                redactor.redact("Authorization: Bearer abc.def.ghi", "httpGet").processedText(),
                "Bearer 凭据整体应脱敏且键名保留");
    }

    @Test
    void JSON形态秘密被脱敏且保持结构() {
        String result = redactor.redact("{\"password\": \"abc123\", \"user\": \"tom\"}", "httpGet").processedText();
        assertEquals("{\"password\": \"[REDACTED]\", \"user\": \"tom\"}", result, "JSON 键值结构应保持");
    }

    @Test
    void 多秘密连续脱敏计数正确() {
        SecretRedactor.Result r = redactor.redact("a=password=one; b=token=two", "httpGet");
        assertEquals(2, r.count(), "多处秘密应分别计数");
        assertEquals("a=password=[REDACTED]; b=token=[REDACTED]", r.processedText());
    }

    @Test
    void 命中后记录WARN且日志不含原秘密值() {
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            redactor.redact("password=hunter2secret", "httpGet");
            List<ILoggingEvent> events = sanitizeEvents(appender);
            assertEquals(1, events.size(), "应记录一条 WARN");
            assertEquals(Level.WARN, events.get(0).getLevel(), "应为 WARN 级别");
            String msg = events.get(0).getFormattedMessage();
            assertTrue(msg.contains("SECRET_REDACTED"), "应含动作标识: " + msg);
            assertTrue(msg.contains("count=1"), "应含命中次数: " + msg);
            assertFalse(msg.contains("hunter2secret"), "日志不应含原始秘密值: " + msg);
        } finally {
            detachAppender(appender);
        }
    }

    // ==================== 反例：零误杀 ====================

    @Test
    void 反例密码学讨论不误杀() {
        String benign = "密码学中 password 与哈希（hash）是核心概念。";
        SecretRedactor.Result r = redactor.redact(benign, "httpGet");
        assertEquals(0, r.count(), "讨论型内容不应命中");
        assertEquals(benign, r.processedText(), "正文应原样保留（AC-E04）");
    }

    @Test
    void 反例示例代码非赋值形态不误杀() {
        String benign = "函数签名：connect(host, password, port) 表示连接参数。";
        assertEquals(0, redactor.redact(benign, "httpGet").count(), "非赋值形态不应命中");
    }

    @Test
    void 反例普通英文正文含secret单词不误杀() {
        String benign = "The secret is that nobody trusts the data blindly.";
        assertEquals(0, redactor.redact(benign, "httpGet").count(), "普通词汇不应命中");
    }

    // ==================== 配置注入与边界 ====================

    @Test
    void 秘密规则组可经配置注入覆盖() {
        ToolSanitizeProperties p = new ToolSanitizeProperties();
        p.setSecretPatterns(List.of("(?i)\\b(?:mykey)\\b[:=]([^\\s,]+)"));
        assertEquals("mykey=[REDACTED]", redactorWith(p).redact("mykey=value123", "httpGet").processedText());
    }

    @Test
    void null与空串安全处理() {
        SecretRedactor.Result nullResult = redactor.redact(null, "httpGet");
        assertNull(nullResult.processedText(), "null 输入应返回 null");
        assertEquals(0, nullResult.count(), "null 输入计数应为 0");
        assertEquals(0, redactor.redact("", "httpGet").count(), "空串计数应为 0");
    }

    // ==================== Task-21 对抗用例库断言化 ====================

    @Test
    void 秘密正例库全部被脱敏() {
        for (String payload : InjectionPayloads.SECRET_POSITIVE) {
            SecretRedactor.Result r = redactor.redact(payload, "httpGet");
            assertTrue(r.count() >= 1, "正例应命中: " + payload);
            assertTrue(r.processedText().contains("[REDACTED]"), "正例应出现 [REDACTED]: " + payload);
        }
    }

    @Test
    void 秘密反例库零误杀() {
        for (String payload : InjectionPayloads.SECRET_BENIGN) {
            SecretRedactor.Result r = redactor.redact(payload, "httpGet");
            assertEquals(0, r.count(), "反例不应命中: " + payload);
            assertEquals(payload, r.processedText(), "反例应原样保留: " + payload);
        }
    }

    private ListAppender<ILoggingEvent> attachAppender() {
        Logger sanitizeLogger = (Logger) LoggerFactory.getLogger(SanitizeLogs.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        sanitizeLogger.addAppender(appender);
        return appender;
    }

    private void detachAppender(ListAppender<ILoggingEvent> appender) {
        Logger sanitizeLogger = (Logger) LoggerFactory.getLogger(SanitizeLogs.class);
        sanitizeLogger.detachAppender(appender);
    }

    private List<ILoggingEvent> sanitizeEvents(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .filter(e -> e.getFormattedMessage().startsWith("[tool-sanitize]"))
                .toList();
    }
}
