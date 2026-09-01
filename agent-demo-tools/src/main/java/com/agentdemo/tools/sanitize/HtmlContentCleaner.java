package com.agentdemo.tools.sanitize;

import cn.hutool.http.HtmlUtil;
import org.springframework.stereotype.Component;

/**
 * HTML 可执行内容剥离段（管道①，order=200，CR-004 起实现 SanitizeStage）
 * <p>
 * 业务含义：剥离 HTML 页面中的可执行内容（AC-S03 script/iframe/object/embed 标签、
 * 事件属性）与内容级危险协议（AC-S04 javascript:/data:/vbscript:），保留正文文本。
 * 剥离动作记录 WARN 安全日志（AC-S07）。
 * </p>
 * <p>
 * 门控（CR-004 归一）：ctx.htmlContent（HttpTool 按运行时 Content-Type 判定）。
 * 实现：Hutool HtmlUtil.removeHtmlTag 整块移除可执行标签及其内容，
 * HtmlUtil.filter 过滤事件属性与危险协议。剥离异常由编排器逐段隔离跳过本段（AC-E06）。
 * </p>
 */
@Component
public class HtmlContentCleaner implements SanitizeStage {

    @Override
    public int order() {
        return 200;
    }

    @Override
    public String name() {
        return "HTML_CLEANER";
    }

    @Override
    public boolean appliesTo(SanitizeContext ctx) {
        return ctx.isHtmlContent();
    }

    /**
     * 剥离 HTML 中的可执行内容
     *
     * @param html 原始 HTML 内容
     * @param ctx  清洗上下文（来源工具名用于安全日志）
     * @return 剥离后的文本；null 输入返回 null
     */
    @Override
    public String process(String html, SanitizeContext ctx) {
        if (html == null) {
            return null;
        }
        // 业务含义：可执行标签连同其内容整块移除（脚本内嵌数据对模型非必要正文，安全优先）
        String result = HtmlUtil.removeHtmlTag(html, true, "script", "iframe", "object", "embed");
        // 业务含义：XSS 过滤移除事件属性（onclick 等）与危险协议（javascript: 等）
        result = HtmlUtil.filter(result);
        SanitizeLogs.warn(ctx.getToolName(), "HTML_CLEANER", "HTML_STRIPPED",
                "tags=script,iframe,object,embed; events=on*; protocols=javascript:,data:,vbscript:");
        return result;
    }
}
