package com.agentdemo.tools.sanitize;

import lombok.Builder;
import lombok.Data;

/**
 * 工具产出清洗上下文
 * <p>
 * 业务含义：描述一次工具产出清洗的元信息，供清洗管道各段使用。
 * toolName 用于包裹声明的来源标识（AC-S06）与安全日志（AC-S07）；
 * htmlContent 标记决定是否触发 HTML 可执行内容剥离（AC-S03）。
 * </p>
 */
@Data
@Builder
public class SanitizeContext {

    /** 来源工具名（如 httpGet / readFile / mcp:{server}/{tool} / rag:{kb}），用于声明来源与日志 */
    private final String toolName;

    /** 来源描述（如"网页内容"），用于包裹声明 */
    private final String sourceDesc;

    /** 是否为 HTML 内容：true 时触发 HtmlContentCleaner 剥离可执行内容 */
    private final boolean htmlContent;
}
