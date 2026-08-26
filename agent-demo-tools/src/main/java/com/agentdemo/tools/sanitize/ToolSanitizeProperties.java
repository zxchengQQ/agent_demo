package com.agentdemo.tools.sanitize;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具产出清洗配置属性
 * <p>
 * 业务含义：集中管理工具产出清洗的所有可配置参数，包括总开关、字数上限、
 * 临时文件目录、MIME 白名单和可疑指令模式规则组。通过 application.yml 中
 * agent.tool.sanitize.* 前缀注入。
 * </p>
 * <p>
 * 约束：temp-dir 必须位于 agent.file-allowed-dir（默认 ./data）内，
 * 否则 Agent 无法通过 readFile 工具分段回读超长内容的剩余部分（AC-T02）。
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.tool.sanitize")
public class ToolSanitizeProperties {

    /** 清洗总开关：false 时所有工具产出直通返回原文（回退开关） */
    private boolean enabled = true;

    /** 工具产出进入上下文的默认最大字符数：超过则前缀进入上下文 + 剩余保存临时文件（AC-T04） */
    private int maxChars = 4000;

    /** 临时文件目录：必须位于 agent.file-allowed-dir 内，否则模型无法回读剩余内容 */
    private String tempDir = "./data/tool-output";

    /** 临时文件保留时长（小时）：写入时机会式清理超期文件 */
    private int tempRetentionHours = 24;

    /** MIME 白名单（HttpTool 响应）：非白名单类型不返回原文，返回可读提示（AC-S02） */
    private List<String> allowedMimeTypes = new ArrayList<>(List.of(
            "text/html", "application/xhtml+xml",
            "text/plain", "text/markdown",
            "application/json", "application/xml", "text/xml",
            "application/*+json", "application/*+xml"
    ));

    /** 一般可疑指令模式（正则）：命中保留原文 + 警示标记（AC-S05 分级处置-一般） */
    private List<String> suspiciousPatterns = new ArrayList<>(List.of(
            "ignore\\s+(all\\s+|the\\s+)?(previous|above|prior)\\s+(instructions|prompts|messages)",
            "disregard\\s+(all\\s+|the\\s+)?(previous|above|prior)",
            "忽略.{0,20}(之前|上述|以上|先前).{0,20}(指令|提示|要求|消息)",
            "不要理会.{0,20}(之前|上述|以上).{0,20}(指令|提示|要求)"
    ));

    /** 高危指令模式（正则）：命中移除该片段 + 占位标记 + WARN 日志（AC-S05 分级处置-高危） */
    private List<String> highRiskPatterns = new ArrayList<>(List.of(
            "你现在是.{0,50}(人工智能|AI|大语言模型|语言模型)",
            "from now on you are .{0,50}(ai|assistant|language model)",
            "(reveal|show|print|display).{0,30}(your|the).{0,10}(system\\s*)?(prompt|instructions)",
            "(把|将).{0,20}(系统提示|system prompt).{0,20}(告诉|泄露|显示|打印|说出)",
            "执行以下.{0,20}(命令|指令).{0,60}(rm\\s+-rf|DELETE\\s+FROM|DROP\\s+TABLE|shutdown|format\\s)"
    ));
}
