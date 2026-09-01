package com.agentdemo.tools.sanitize;

/**
 * 清洗管道变换段 SPI（CR-004，技术方案 §3.1）
 * <p>
 * 业务含义：工具产出清洗管道的变换段（⓪ 隐形字符剥离 / ① HTML 剥离 / ② 可疑指令分级处置 /
 * ②' 秘密脱敏，及后续 CR-002 注入检测引擎等）统一实现的阶段接口。编排器
 * （ToolOutputSanitizer）按 {@link #order()} 升序遍历执行，新增阶段实现 bean
 * 即插即用接入、编排器零修改（AC-T05）。
 * </p>
 * <p>
 * 边界：本 SPI 只覆盖"变换段"（清洗逻辑可插拔）；终段③限长临时文件与④包裹声明
 * 定义产物契约，固定在编排器内不参与插拔（技术决策 12）。
 * </p>
 */
public interface SanitizeStage {

    /**
     * 阶段执行顺序（数值小先执行）
     * <p>
     * 既有段位：⓪ 隐形字符剥离=100 / ① HTML 剥离=200 / ② 可疑指令分级=300 / ②' 秘密脱敏=400；
     * CR-002 检测引擎预留 500+。段序语义见技术方案 §3.1（⓪ 置于最前保证正则检测面对可见文本，
     * ②' 位于检测后限长前保证落盘内容为已脱敏文本）。
     * </p>
     */
    int order();

    /**
     * 阶段名（用于段级降级 WARN 日志的规则位，AC-S07）
     */
    String name();

    /**
     * 本段是否对当前上下文生效（门控归一，CR-004）
     * <p>
     * 既有门控语义：⓪ invisible-chars 开关 / ① ctx.htmlContent / ② 无条件 /
     * ②' redact-secrets 开关。返回 false 时编排器跳过本段（不调用 process）。
     * </p>
     */
    default boolean appliesTo(SanitizeContext ctx) {
        return true;
    }

    /**
     * 变换文本（前段产物是后段输入）
     *
     * @param text 待处理文本（不为 null，由编排器保证）
     * @param ctx  清洗上下文（来源工具名等）
     * @return 处理后的文本
     */
    String process(String text, SanitizeContext ctx);
}
