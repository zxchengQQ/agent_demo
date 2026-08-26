package com.agentdemo.tools.sanitize;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HtmlContentCleaner 单元测试
 * <p>
 * 验证标准来源：Task-04 验证标准
 * 业务含义：验证 HTML 可执行内容剥离（AC-S03 script/iframe/object/embed/事件属性）与
 * 内容级危险协议限制（AC-S04 javascript:/data:/vbscript:），同时保证正文文本保留。
 * </p>
 */
class HtmlContentCleanerTest {

    private final HtmlContentCleaner cleaner = new HtmlContentCleaner();

    @Test
    void script标签被剥离且正文保留() {
        String html = "<html><body><script>alert(1)</script>你好世界</body></html>";
        String result = cleaner.clean(html, "httpGet");
        assertFalse(result.toLowerCase().contains("<script"), "script 标签应被移除");
        assertTrue(result.contains("你好世界"), "正文文本应保留");
    }

    @Test
    void iframe_object_embed标签被剥离() {
        String html = "<iframe src=\"https://evil.example.com\"></iframe>" +
                "<object data=\"x.swf\"></object><embed src=\"y.swf\">正文内容";
        String result = cleaner.clean(html, "httpGet");
        assertFalse(result.contains("<iframe"), "iframe 应被移除");
        assertFalse(result.contains("<object"), "object 应被移除");
        assertFalse(result.contains("<embed"), "embed 应被移除");
        assertTrue(result.contains("正文内容"), "正文应保留");
    }

    @Test
    void 事件属性被移除() {
        String html = "<div onclick=\"alert(1)\" onmouseover=\"steal()\">内容</div>";
        String result = cleaner.clean(html, "httpGet");
        assertFalse(result.contains("onclick"), "onclick 事件属性应被移除");
        assertFalse(result.contains("onmouseover"), "onmouseover 事件属性应被移除");
        assertTrue(result.contains("内容"), "div 文本内容应保留");
    }

    @Test
    void 危险协议引用被移除安全协议保留() {
        String html = "<a href=\"javascript:void(0)\">链接1</a>" +
                "<a href=\"data:text/html;base64,xxx\">链接2</a>" +
                "<a href=\"vbscript:msgbox(1)\">链接3</a>" +
                "<a href=\"https://safe.example.com\">安全链接</a>" +
                "<a href=\"/relative/path\">相对路径</a>";
        String result = cleaner.clean(html, "httpGet");
        assertFalse(result.toLowerCase().contains("javascript:"), "javascript: 协议应被移除");
        assertFalse(result.toLowerCase().contains("data:text"), "data: 协议应被移除");
        assertFalse(result.toLowerCase().contains("vbscript:"), "vbscript: 协议应被移除");
        assertTrue(result.contains("https://safe.example.com"), "https 安全协议应保留");
        assertTrue(result.contains("/relative/path"), "相对路径应保留");
        assertTrue(result.contains("安全链接"), "链接文本应保留");
    }

    @Test
    void 纯文本不受影响() {
        String text = "这是一段纯文本内容，没有 HTML 标签";
        assertEquals(text, cleaner.clean(text, "httpGet"), "纯文本应原样返回");
    }

    @Test
    void 畸形HTML不抛异常() {
        String html = "<script>alert(1)<div>未闭合标签<p>嵌套异常</script></body>";
        assertDoesNotThrow(() -> cleaner.clean(html, "httpGet"), "畸形 HTML 不应抛异常");
    }

    @Test
    void null输入安全返回null() {
        assertNull(cleaner.clean(null, "httpGet"), "null 输入应安全返回 null");
    }
}
