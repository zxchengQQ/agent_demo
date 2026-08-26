package com.agentdemo.tools.sanitize;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolSanitizeProperties 单元测试
 * <p>
 * 验证标准来源：Task-01 验证标准
 * 业务含义：验证清洗配置类的默认值、字段绑定与可覆盖性（AC-T04 字数上限可配置）。
 * </p>
 */
class ToolSanitizePropertiesTest {

    @Test
    void 默认值断言() {
        ToolSanitizeProperties props = new ToolSanitizeProperties();
        assertTrue(props.isEnabled(), "清洗总开关默认应开启");
        assertEquals(4000, props.getMaxChars(), "默认最大字符数应为 4000");
        assertEquals("./data/tool-output", props.getTempDir(), "默认临时目录应为 ./data/tool-output");
        assertEquals(24, props.getTempRetentionHours(), "默认临时文件保留时长应为 24 小时");
    }

    @Test
    void 配置值可覆盖() {
        ToolSanitizeProperties props = new ToolSanitizeProperties();
        props.setMaxChars(6000);
        props.setEnabled(false);
        props.setTempDir("./data/custom-output");
        props.setTempRetentionHours(48);
        assertEquals(6000, props.getMaxChars(), "maxChars 应可覆盖");
        assertFalse(props.isEnabled(), "enabled 应可覆盖");
        assertEquals("./data/custom-output", props.getTempDir(), "tempDir 应可覆盖");
        assertEquals(48, props.getTempRetentionHours(), "tempRetentionHours 应可覆盖");
    }

    @Test
    void 默认MIME白名单非空且含text_html() {
        ToolSanitizeProperties props = new ToolSanitizeProperties();
        assertNotNull(props.getAllowedMimeTypes(), "MIME 白名单不应为 null");
        assertTrue(props.getAllowedMimeTypes().contains("text/html"),
                "MIME 白名单应包含 text/html");
        assertTrue(props.getAllowedMimeTypes().contains("application/json"),
                "MIME 白名单应包含 application/json");
    }

    @Test
    void 默认可疑与高危规则组非空() {
        ToolSanitizeProperties props = new ToolSanitizeProperties();
        assertNotNull(props.getSuspiciousPatterns(), "一般可疑规则组不应为 null");
        assertNotNull(props.getHighRiskPatterns(), "高危规则组不应为 null");
        assertFalse(props.getSuspiciousPatterns().isEmpty(), "一般可疑规则组应有默认规则");
        assertFalse(props.getHighRiskPatterns().isEmpty(), "高危规则组应有默认规则");
    }

    @Test
    void 自定义规则可经配置注入() {
        ToolSanitizeProperties props = new ToolSanitizeProperties();
        props.setSuspiciousPatterns(java.util.List.of("自定义一般规则"));
        props.setHighRiskPatterns(java.util.List.of("自定义高危规则"));
        assertEquals(1, props.getSuspiciousPatterns().size(), "一般规则组应可注入覆盖");
        assertEquals("自定义一般规则", props.getSuspiciousPatterns().get(0));
        assertEquals("自定义高危规则", props.getHighRiskPatterns().get(0));
    }
}
