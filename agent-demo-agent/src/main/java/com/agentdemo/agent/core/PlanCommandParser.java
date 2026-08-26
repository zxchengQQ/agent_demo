package com.agentdemo.agent.core;

/**
 * /plan 强制拆解指令解析器（unified-chat-mode 新增）
 * <p>
 * 业务含义：统一对话模式下用户通过 "/plan" 消息前缀强制指定任务拆解。
 * 解析必须发生在 Controller 入口（写记忆前）一次性完成，保证控制指令
 * 不进入推理上下文（需求 6.6 提示注入防护：工具返回/知识库内容中的
 * "/plan" 字样不触发强制拆解，因为只有用户消息会经过本解析器）。
 * </p>
 * <p>
 * 关联 AC：AC-N04（/plan 强制拆解）、AC-E02（空内容判定基础）
 * </p>
 */
public final class PlanCommandParser {

    /** 强制拆解指令前缀（仅识别小写，避免 /PLAN 等变体误触发） */
    private static final String PLAN_COMMAND = "/plan";

    private PlanCommandParser() {
    }

    /**
     * 解析用户消息中的 /plan 前缀指令
     * <p>
     * 规则：trim 后以 /plan（后跟空白或串尾）开头则 forced=true 并剥离前缀，
     * 剥离后的内容作为任务主题（"/plan任务"无分隔符不识别、"/plans"等其他单词不识别）。
     * </p>
     *
     * @param message 原始用户消息（可为 null）
     * @return 解析结果：forced 标志 + 剥离前缀后的内容
     */
    public static PlanCommand parse(String message) {
        if (message == null) {
            return new PlanCommand(false, null);
        }
        String trimmed = message.trim();
        // 前缀匹配须满足"/plan后跟空白或串尾"，防止/planXYZ、/plans等误识别
        boolean matches = trimmed.startsWith(PLAN_COMMAND)
                && (trimmed.length() == PLAN_COMMAND.length()
                        || Character.isWhitespace(trimmed.charAt(PLAN_COMMAND.length())));
        if (matches) {
            String content = trimmed.substring(PLAN_COMMAND.length()).trim();
            return new PlanCommand(true, content);
        }
        return new PlanCommand(false, trimmed);
    }

    /**
     * /plan 指令解析结果
     *
     * @param forced  是否命中强制拆解指令
     * @param content 剥离前缀后的任务内容（未命中时为原消息 trim 后内容）
     */
    public record PlanCommand(boolean forced, String content) {
    }
}
