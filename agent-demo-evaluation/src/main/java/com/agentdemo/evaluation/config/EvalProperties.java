package com.agentdemo.evaluation.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 评估 harness 配置（langsmith-observability CR-002 Task-26，决策 14）
 * <p>
 * 业务含义：本地评估的触发与路径配置（application.yml {@code eval.*}）——
 * 默认全部关闭/指向 data/eval 下产物文件；judge 模型可配置（决策 13），
 * 配置版本用于基线追溯。默认不随构建/启动执行（按需手动触发，AC-E05 生态）。
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "eval")
public class EvalProperties {

    /** 总开关：false 时评估 harness Bean 不装配（零影响） */
    private boolean enabled = false;

    /** 数据集路径（JSON） */
    private String datasetPath = "data/eval/dataset.json";

    /** 基线路径（JSON，首建后仅显式重建覆盖） */
    private String baselinePath = "data/eval/baseline.json";

    /** 评估报告输出路径（Markdown） */
    private String reportPath = "data/eval/report.md";

    /** 每用例运行次数（Pass^runs，A/B 强制下限 3） */
    private int runs = 3;

    /** judge 模型 ID（空则跳过 judge 维度，全缺席标注） */
    private String judgeModelId = "";

    /** 被评 Agent 模型 ID（空则回退默认模型，用于同源 WARN） */
    private String agentModelId = "";

    /** judge/数据集出境脱敏字段上限（字符） */
    private int maskMaxChars = 4000;

    /** 配置/Prompt 版本（基线追溯与对比报告） */
    private String configVersion = "unknown";

    /** 启动即执行一次评估（默认 false：按需手动触发） */
    private boolean runOnStartup = false;

    /** 合成厂商名（CLI 独立上下文 LLM 配置种子注入用，真实评估前置） */
    private String vendorName = "eval-cli";

    /** 合成厂商 Base URL（默认火山引擎方舟 Coding Plan；OpenAI 兼容协议端点） */
    private String baseUrl = "https://ark.cn-beijing.volces.com/api/coding/v3";

    /** 合成厂商 API Key（CLI 上下文无 web 配置面，默认经 ${ARK_API_KEY:} 注入，禁止硬编码） */
    private String apiKey = "";

    /** judge 独立厂商名（多源 judge：判官与被评 Agent 不同厂商/模型家族，对冲同源偏差） */
    private String judgeVendorName = "eval-judge-cli";

    /** judge 独立 Base URL（空=与被评 Agent 同厂商复用 eval-cli） */
    private String judgeBaseUrl = "";

    /** judge 独立 API Key（空=与被评 Agent 同 Key；禁止硬编码） */
    private String judgeApiKey = "";
}
