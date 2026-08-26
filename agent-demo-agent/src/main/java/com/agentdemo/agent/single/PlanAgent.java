package com.agentdemo.agent.single;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.agent.core.HumanInteractionManager;
import com.agentdemo.agent.core.TaskPlanJudge;
import com.agentdemo.agent.core.UnifiedChatStream;
import com.agentdemo.agent.prompt.PromptTemplateLoader;
import com.agentdemo.llm.registry.ModelFactory;
import com.agentdemo.memory.shortterm.ChatMemoryManager;
import com.agentdemo.tools.registry.ToolExecutor;
import com.agentdemo.tools.registry.ToolSchemaConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 任务拆解 Agent（CR-002 新增，unified-chat-mode 改造）
 * <p>
 * 业务含义：接收用户消息，创建 TaskBreakdownStream 进行拆解执行编排。
 * unified-chat-mode：新增统一模式工厂（chatUnifiedStream/resumeUnifiedStream），
 * 拆解编排内化为 UnifiedChatStream 内部委托。
 * </p>
 * <p>
 * 依赖注入：ModelFactory（LLM 模型）、ChatMemoryManager（会话记忆）、
 * AgentConfig（配置）、ToolSchemaConverter（工具 Schema）、ToolExecutor（工具执行）、
 * HumanInteractionManager（HITL 暂停-恢复）
 * </p>
 */
@Service
public class PlanAgent {

    private static final Logger log = LoggerFactory.getLogger(PlanAgent.class);

    private final ModelFactory modelFactory;
    private final ChatMemoryManager memoryManager;
    private final AgentConfig agentConfig;
    private final ToolSchemaConverter toolSchemaConverter;
    private final ToolExecutor toolExecutor;
    private final PromptTemplateLoader promptTemplateLoader;
    private final HumanInteractionManager humanInteractionManager;
    private final SessionToolResolver sessionToolResolver;
    private final TaskPlanJudge taskPlanJudge;

    /**
     * 构造器注入（禁止 @Autowired 字段注入）
     *
     * @param modelFactory        模型工厂（提供 ChatModel 和 ArkThinkingStreamingChatModel）
     * @param memoryManager       记忆管理器（会话级短期记忆）
     * @param agentConfig         Agent 配置（提示词、迭代次数等）
     * @param toolSchemaConverter 工具 Schema 转换器（工具描述和 JSON Schema）
     * @param toolExecutor        工具执行器（ReAct 循环中执行工具调用）
     * @param promptTemplateLoader 提示词模板加载器（角色+场景模板组合）
     * @param humanInteractionManager 人机交互管理器（HITL 暂停-恢复）
     */
    public PlanAgent(ModelFactory modelFactory,
                     ChatMemoryManager memoryManager,
                     AgentConfig agentConfig,
                     ToolSchemaConverter toolSchemaConverter,
                     ToolExecutor toolExecutor,
                     PromptTemplateLoader promptTemplateLoader,
                     HumanInteractionManager humanInteractionManager,
                     SessionToolResolver sessionToolResolver,
                     TaskPlanJudge taskPlanJudge) {
        this.modelFactory = modelFactory;
        this.memoryManager = memoryManager;
        this.agentConfig = agentConfig;
        this.toolSchemaConverter = toolSchemaConverter;
        this.toolExecutor = toolExecutor;
        this.promptTemplateLoader = promptTemplateLoader;
        this.humanInteractionManager = humanInteractionManager;
        this.sessionToolResolver = sessionToolResolver;
        this.taskPlanJudge = taskPlanJudge;
        log.info("PlanAgent 构造完成");
    }

    // ==================== unified-chat-mode 统一模式工厂 ====================

    /**
     * 统一模式流式对话（首次/强制拆解）
     * <p>
     * 业务含义：创建统一编排核心 UnifiedChatStream。强制拆解经 /plan 前缀表达
     * （Controller 已解析并剥离），此处 forcedBreakdown 标识是否强制拆解。
     * </p>
     *
     * @param sessionId        会话 ID
     * @param message          用户消息（已剥离 /plan 前缀）
     * @param modelId          模型 ID（null 使用默认模型）
     * @param toolIds          工具标识列表
     * @param forcedBreakdown  /plan 强制拆解标记
     * @return UnifiedChatStream 实例（需调用 start() 启动）
     */
    public UnifiedChatStream chatUnifiedStream(String sessionId, String message, String modelId,
                                               List<String> toolIds, boolean forcedBreakdown) {
        log.info("Agent 统一模式流式对话: sessionId={}, forcedBreakdown={}", sessionId, forcedBreakdown);
        return new UnifiedChatStream(
                sessionId, message, modelId, forcedBreakdown, false, toolIds, null,
                modelFactory, memoryManager, agentConfig, toolSchemaConverter, toolExecutor,
                promptTemplateLoader, humanInteractionManager, sessionToolResolver, taskPlanJudge);
    }

    /**
     * 统一模式恢复流式对话（用户回复后）
     * <p>
     * 业务含义：按 pending.mode 构造 UnifiedChatStream 恢复实例（resumeMode=true）。
     * 恢复路径不解析 /plan（回复中的 /plan 视为普通文本，决策 6）。
     * 旧签名委托三参重载且 approved=null——普通 HITL askUser 恢复链路零变更（AC-E02 兼容）。
     * </p>
     *
     * @param sessionId 会话 ID
     * @param userReply 用户回复文本
     * @return UnifiedChatStream 恢复实例
     */
    public UnifiedChatStream resumeUnifiedStream(String sessionId, String userReply) {
        return resumeUnifiedStream(sessionId, userReply, null);
    }

    /**
     * 统一模式恢复流式对话（带权限确认结果）
     * <p>
     * 业务含义：tool_confirm 暂停后用户批准/拒绝的传递通道（AC-S02）——
     * approved=true 批准执行待确认工具（AC-N03），false/null 拒绝（回填拒绝文案）。
     * 仅 tool_confirm 恢复模式生效，其余模式忽略该参数。
     * </p>
     *
     * @param sessionId 会话 ID
     * @param userReply 用户回复文本（UI 展示用，不进入推理上下文）
     * @param approved  权限确认结果（true=批准，false/null=拒绝）
     * @return UnifiedChatStream 恢复实例
     */
    public UnifiedChatStream resumeUnifiedStream(String sessionId, String userReply, Boolean approved) {
        log.info("Agent 统一模式恢复: sessionId={}, approved={}", sessionId, approved);
        return new UnifiedChatStream(
                sessionId, userReply, null, false, true, null, approved,
                modelFactory, memoryManager, agentConfig, toolSchemaConverter, toolExecutor,
                promptTemplateLoader, humanInteractionManager, sessionToolResolver, taskPlanJudge);
    }
}
