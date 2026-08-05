package com.agentdemo.llm.thinking;

import lombok.Data;

/**
 * 工具调用数据类（Task-02 新增）
 * <p>
 * 业务含义：表示 LLM 流式响应中的工具调用信息，由方舟 SSE 流的 delta.tool_calls 解析而来。
 * 当模型决定调用外部工具时，通过 ThinkingStreamHandler.onToolCalls 回调传递给调用方。
 * </p>
 */
@Data
public class ToolCall {

    /**
     * 工具调用 ID（对应方舟 tool_calls[].id）
     */
    private String id;

    /**
     * 工具名称（对应方舟 tool_calls[].function.name）
     */
    private String functionName;

    /**
     * 参数 JSON 字符串（对应方舟 tool_calls[].function.arguments）
     */
    private String arguments;
}
