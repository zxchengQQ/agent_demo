package com.agentdemo.tools.builtin;

import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 人机交互工具
 * <p>
 * 业务含义：当 Agent 需要向用户提问/确认时调用此工具。HITLReActStream 会拦截此工具调用，
 * 不实际执行方法体，而是触发暂停流程（保存状态 -> 发送 ask_user SSE 事件 -> 等待用户回复）。
 * 在非 HITL 模式下，方法体返回占位文本，LLM 会将其作为 Observation 理解。
 * </p>
 */
@Component
public class AskUserTool {

    /**
     * 向用户提问或确认
     * <p>
     * 业务含义：Agent 调用此工具暂停执行并向用户提问。type=text 用于开放式追问（缺少参数/歧义消除），
     * type=confirm 用于关键操作确认（有副作用的操作前确认）。
     * </p>
     *
     * @param type     提问类型：text（开放式追问）或 confirm（确认型交互）
     * @param question 问题文本，如"请提供订单号"或"确认删除文件XXX？"
     * @param options  选项列表，confirm 类型必填（2-4 个选项），text 类型可传空数组
     * @return 用户回复文本（HITL 模式下由 HITLReActStream 拦截，此返回值为占位文本）
     */
    @Tool("当需要向用户提问或确认时调用此工具。"
            + "适用场景：用户指令缺少必要参数或存在歧义时（如缺少订单号、时间范围不明确）；"
            + "面临多种方案需要用户选择时；即将执行有副作用的操作前需确认时（如删除文件、发送请求）。"
            + "不适用场景：信息充足可自主推进时；无副作用的查询或计算操作时（如计算表达式、获取时间）。"
            + "参数 type 为提问类型（text 或 confirm）；question 为问题文本；options 为选项列表（confirm 类型必填，2-4 个选项）。"
            + "调用后 Agent 将暂停执行，等待用户回复后继续。")
    public String askUser(String type, String question, String[] options) {
        // 业务含义：占位实现。HITL 模式下由 HITLReActStream 拦截，不执行此方法。
        // 非 HITL 模式下返回提示文本，LLM 会将其作为 Observation 理解。
        return "[askUser] 已向用户提问: " + question + "，等待用户回复。";
    }
}
