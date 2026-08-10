package com.agentdemo.mcp.tool;

import dev.langchain4j.agent.tool.Tool;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;

/**
 * ByteBuddy 方法拦截器 - MCP 工具调用中转站
 * <p>
 * 业务含义：ByteBuddy 动态生成的 @Tool 方法被调用时，会先经过此拦截器，
 * 拦截器持有 serverName 和 toolName（在工具生成时绑定），把方法参数（argsJson）
 * 转交给 McpToolExecutor.execute 统一处理。
 * </p>
 * <p>
 * 设计原则：
 * 1. 无状态绑定：serverName/toolName 在构造时绑定，方法签名仅保留 argsJson 单参数
 * 2. 异常透传：toolExecutor 抛出的 BusinessException 直接传播给 Agent，由 Agent 处理
 * 3. 健壮性：参数缺失时抛 IllegalStateException 而非 ArrayIndexOutOfBoundsException
 * </p>
 * <p>
 * 参考：CR-003 KnowledgeBaseToolFactory.SearchInterceptor 的实现模式
 * </p>
 */
public class McpToolInterceptor {

    /** MCP Server 名称（工具生成时绑定） */
    private final String serverName;

    /** 工具原始名称（不带 mcp_ 前缀，工具生成时绑定） */
    private final String toolName;

    /** 工具执行器（Spring 单例，所有拦截器共享） */
    private final McpToolExecutor toolExecutor;

    public McpToolInterceptor(String serverName, String toolName, McpToolExecutor toolExecutor) {
        this.serverName = serverName;
        this.toolName = toolName;
        this.toolExecutor = toolExecutor;
    }

    /**
     * 拦截动态 Tool 方法调用
     * <p>
     * 业务含义：将 ByteBuddy 生成的方法调用委托给 McpToolExecutor.execute，
     * 在拦截器中绑定 serverName 和 toolName，使方法签名只保留 argsJson 一个参数。
     * </p>
     *
     * @param args 方法参数数组（此处仅含 argsJson）
     * @return 工具执行结果文本
     */
    @RuntimeType
    public String execute(@AllArguments Object[] args) {
        if (args == null || args.length == 0) {
            // 参数缺失属于 ByteBuddy 调用机制异常，不应在正常运行时出现
            throw new IllegalStateException(
                    "MCP 工具调用参数缺失 [" + serverName + "/" + toolName + "]");
        }
        String argsJson = (String) args[0];
        return toolExecutor.execute(serverName, toolName, argsJson);
    }
}
