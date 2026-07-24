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
     * 默认系统提示词（用于正常模式，支持工具调用）
     */
    private String defaultSystemPrompt = "你是一个有用的 AI 助手，可以调用工具帮助用户解决问题。当问题需要计算、查询时间、获取网络信息时，请主动调用相应工具。";

    /**
     * 深度思考模式专用系统提示词
     * <p>
     * 业务含义：思考模式（chatThinkingStream）直连方舟 API，不经过 AiServices ReAct 循环，
     * 不传递工具定义给 API，因此提示词中不应提及工具调用能力，
     * 避免模型在推理过程中尝试调用不存在的工具。
     * </p>
     */
    private String thinkingSystemPrompt = "你是一个有用的 AI 助手。请基于你的知识直接回答用户的问题，无需调用任何工具。";

    /**
     * 深度思考 ReAct 模式最大迭代次数
     * <p>
     * 业务含义：深度思考模式下 ReAct 循环（推理->工具调用->观察->继续推理）的最大轮数，
     * 超过后强制让 LLM 基于已有信息生成总结性回答。默认 8 轮。
     * </p>
     */
    private int thinkingMaxIterations = 8;

    /**
     * 深度思考 ReAct 模式专用系统提示词
     * <p>
     * 业务含义：含 ReAct 格式引导（Thought/Action/Observation 结构化标签）和约束规则，
     * 引导 LLM 在回复中输出显式推理过程。工具能力描述通过 ToolSchemaConverter.convertToDescriptionText()
     * 运行时动态生成并拼接到此提示词末尾（CR-001 修改：移除硬编码工具描述）。
     * 同时通过 tools 参数传递工具 JSON Schema 定义。
     * </p>
     */
    private String thinkingReactSystemPrompt = "你是一个深度思考的 AI Agent，具备工具调用能力。"
            + "请在回答前按照以下 ReAct 格式输出推理过程：\n"
            + "Thought: 分析用户需求，思考下一步行动（可用自然语言展开详细分析）\n"
            + "Action: 描述你打算调用的工具及原因（实际调用由系统处理）\n"
            + "Observation: （系统填入工具返回结果）\n"
            + "Thought: 基于观察结果继续思考\n"
            + "...（可多轮 Thought-Action-Observation）\n"
            + "Final Answer: 最终回答\n\n"
            + "重要约束：\n"
            + "1. 只能使用系统提供的工具（通过 tools 参数传入），不要编造或猜测工具名。\n"
            + "2. 如果用户的问题超出已有工具的能力范围（如天气查询、股票行情等），不要反复尝试调用不存在的工具，"
            + "直接基于自身知识回答或告知用户该功能暂不支持。\n"
            + "3. 当工具返回错误信息时，分析原因后决定下一步，不要盲目重试同一个失败的工具调用。";

    /**
     * 是否启用调用日志
     */
    private boolean enableLogging = true;

    /**
     * 文件读取工具允许的目录
     */
    private String fileAllowedDir = "./data";

    // ==================== CR-002: 复杂任务拆解配置 ====================

    /**
     * 任务拆解 - 规划提示词
     * <p>
     * 业务含义：指导 LLM 分析任务复杂度并生成子任务列表。
     * LLM 根据此提示词判断是否需要拆解，如需拆解则以 JSON 数组格式返回子任务列表。
     * </p>
     */
    private String taskBreakdownPlanPrompt = """
            你是一个任务规划专家。分析用户的任务，判断是否需要拆解为多个子任务。

            规则：
            1. 简单任务（如问候、查询时间、简单计算）不需要拆解
            2. 复杂任务（如调研、分析、多步骤操作）需要拆解
            3. 最多拆解为 10 个子任务
            4. 每个子任务应该是一个可独立执行的明确步骤

            请以 JSON 数组格式返回，格式如下：
            - 需要拆解：[{"title": "子任务标题1"}, {"title": "子任务标题2"}]
            - 不需要拆解：[]

            只返回 JSON 数组，不要其他内容。
            """;

    /**
     * 任务拆解 - 子任务执行系统提示词
     * <p>
     * 业务含义：指导 LLM 执行单个子任务，支持 ReAct 工具调用。
     * 工具描述通过 ToolSchemaConverter.convertToDescriptionText() 运行时动态拼接。
     * </p>
     */
    private String taskExecutionSystemPrompt = """
            你是一个任务执行专家。请执行分配给你的子任务。

            你可以使用以下工具来完成任务。如果需要使用工具，请按照 ReAct 格式调用。
            完成任务后，请给出该子任务的执行结果摘要。
            """;

    /**
     * 任务拆解 - 总结提示词
     * <p>
     * 业务含义：指导 LLM 根据各子任务结果生成最终总结。
     * 在所有子任务执行完成后调用，概括主要发现、结论和建议。
     * </p>
     */
    private String taskSummaryPrompt = """
            请根据以上各子任务的执行结果，生成一份简洁的总结。
            概括主要发现、结论和建议。不要重复每个子任务的详细过程。
            """;

    /**
     * 任务拆解 - 子任务执行最大 ReAct 迭代次数
     * <p>
     * 业务含义：每个子任务内部 ReAct 循环的最大轮数，防止无限循环消耗 Token。
     * 超过后强制让 LLM 基于已有信息生成该子任务的总结性回答。
     * </p>
     */
    private int taskExecutionMaxIterations = 8;

    /**
     * 任务拆解 - 子任务数量上限
     * <p>
     * 业务含义：LLM 生成的子任务列表超过此值时自动截断（AC-008, AC-013）。
     * </p>
     */
    private int taskBreakdownMaxSubtasks = 10;
}
