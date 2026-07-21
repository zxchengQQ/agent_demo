package com.agentdemo.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 配置
 * <p>
 * 业务含义：集中管理 Agent 行为参数，包括最大迭代次数、记忆窗口大小、默认系统提示词等。
 * </p>
 * <p>
 * 配置示例（application.yml）：
 * <pre>
 * agent:
 *   max-iterations: 10
 *   chat-memory-window-size: 20
 *   default-system-prompt: "你是一个有用的 AI 助手"
 *   enable-logging: true
 * </pre>
 * </p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "agent")
public class AgentConfig {

    /**
     * 最大 ReAct 循环迭代次数
     * 业务含义：防止 Agent 无限循环消耗 Token，超过此次数后强制返回当前结果
     */
    private int maxIterations = 10;

    /**
     * 记忆窗口大小（保留最近 N 条消息）
     */
    private int chatMemoryWindowSize = 20;

    /**
     * 默认系统提示词
     */
    private String defaultSystemPrompt = "你是一个有用的 AI 助手，可以调用工具帮助用户解决问题。当问题需要计算、查询时间、获取网络信息时，请主动调用相应工具。";

    /**
     * 是否启用调用日志
     */
    private boolean enableLogging = true;

    /**
     * 文件读取工具允许的目录
     */
    private String fileAllowedDir = "./data";
}
