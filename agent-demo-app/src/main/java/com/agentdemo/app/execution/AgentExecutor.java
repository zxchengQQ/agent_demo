package com.agentdemo.app.execution;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.agent.core.HumanInteractionManager;
import com.agentdemo.agent.prompt.PromptTemplateLoader;
import com.agentdemo.agent.single.HITLReActStream;
import com.agentdemo.agent.single.SessionToolResolver;
import com.agentdemo.app.adapter.AgenticAgentFactory;
import com.agentdemo.app.core.AgentDefinition;
import com.agentdemo.app.core.HumanCheckpoint;
import com.agentdemo.app.service.WorkflowCancelledException;
import com.agentdemo.app.service.WorkflowHITLException;
import com.agentdemo.app.service.WorkflowHITLState;
import com.agentdemo.app.service.WorkflowPausedException;
import com.agentdemo.app.service.WorkflowTimeoutException;
import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.llm.registry.ModelFactory;
import com.agentdemo.llm.thinking.ThinkingStreamingChatModel;
import com.agentdemo.tools.registry.ToolExecutor;
import com.agentdemo.tools.registry.ToolRegistry;
import com.agentdemo.tools.registry.ToolSchemaConverter;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.service.TokenStream;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 执行器
 * <p>
 * 业务含义：封装单个 Agent 的构建 + 失败重试 + 流式输出，供所有执行策略复用。
 * 从 P1 的 WorkflowExecutionService 抽取，实现单一职责（策略模式基础设施层）。
 * </p>
 * <p>
 * 核心方法：
 * 1. executeWithRetry() 失败自动重试，推送 step_retry/step_error 事件（AC-015/AC-026）
 * 2. executeStreaming() 通过 TokenStream 回调捕获流式输出并推送 token 事件（AC-009）
 * 3. checkCancelled() 静态取消标志检查（AC-022）
 * </p>
 */
@Component
public class AgentExecutor {

    /** 单个 Agent 执行超时阈值（分钟），测试时可注入更小值 */
    private final long agentTimeoutMinutes;

    private final AgenticAgentFactory agentFactory;

    // ===== Task-06 新增：HITL 显式 ReAct 依赖（hitlEnabled=true 时使用，默认 null 走 TokenStream 零回归）=====
    private final ModelFactory modelFactory;
    private final ToolRegistry toolRegistry;
    private final ToolSchemaConverter toolSchemaConverter;
    private final ToolExecutor toolExecutor;
    private final PromptTemplateLoader promptTemplateLoader;
    private final HumanInteractionManager humanInteractionManager;
    private final AgentConfig agentConfig;
    private final SessionToolResolver sessionToolResolver;

    /**
     * 默认构造器（Spring 注入，默认 5 分钟超时）
     *
     * @param agentFactory Agent 构建工厂
     */
    @org.springframework.beans.factory.annotation.Autowired
    public AgentExecutor(AgenticAgentFactory agentFactory,
                         ModelFactory modelFactory, ToolRegistry toolRegistry,
                         ToolSchemaConverter toolSchemaConverter, ToolExecutor toolExecutor,
                         PromptTemplateLoader promptTemplateLoader,
                         HumanInteractionManager humanInteractionManager,
                         AgentConfig agentConfig, SessionToolResolver sessionToolResolver) {
        this(agentFactory, 5, modelFactory, toolRegistry, toolSchemaConverter, toolExecutor,
                promptTemplateLoader, humanInteractionManager, agentConfig, sessionToolResolver);
    }

    /**
     * 全参数构造器（测试可注入超时阈值 + HITL 依赖）
     */
    public AgentExecutor(AgenticAgentFactory agentFactory, long agentTimeoutMinutes,
                         ModelFactory modelFactory, ToolRegistry toolRegistry,
                         ToolSchemaConverter toolSchemaConverter, ToolExecutor toolExecutor,
                         PromptTemplateLoader promptTemplateLoader,
                         HumanInteractionManager humanInteractionManager,
                         AgentConfig agentConfig, SessionToolResolver sessionToolResolver) {
        this.agentFactory = agentFactory;
        this.agentTimeoutMinutes = agentTimeoutMinutes;
        this.modelFactory = modelFactory;
        this.toolRegistry = toolRegistry;
        this.toolSchemaConverter = toolSchemaConverter;
        this.toolExecutor = toolExecutor;
        this.promptTemplateLoader = promptTemplateLoader;
        this.humanInteractionManager = humanInteractionManager;
        this.agentConfig = agentConfig;
        this.sessionToolResolver = sessionToolResolver;
    }

    /**
     * 测试便捷构造器（HITL 依赖为 null，hitlEnabled=false 时零回归）
     *
     * @param agentFactory Agent 构建工厂
     */
    public AgentExecutor(AgenticAgentFactory agentFactory) {
        this(agentFactory, 5, null, null, null, null, null, null, null, null);
    }

    /**
     * 测试便捷构造器（注入超时阈值，HITL 依赖为 null）
     *
     * @param agentFactory        Agent 构建工厂
     * @param agentTimeoutMinutes 单个 Agent 执行超时阈值（分钟）
     */
    public AgentExecutor(AgenticAgentFactory agentFactory, long agentTimeoutMinutes) {
        this(agentFactory, agentTimeoutMinutes, null, null, null, null, null, null, null, null);
    }

    /**
     * 执行单个 Agent（含自动重试）
     * <p>
     * 业务含义：首次执行失败后自动重试，重试次数由模板 maxRetries 决定（AC-026）。
     * 每次重试推送 step_retry 事件，重试耗尽后推送 step_error 并抛出异常（AC-015）。
     * HITL 暂停信号（@HumanCheckpoint 检查点 / askUser 拦截）不重试，直接上抛（AC-N02）。
     * </p>
     *
     * @param agentDef   Agent 定义
     * @param input      输入文本
     * @param emitter    SSE 发射器
     * @param agentIndex Agent 索引（用于事件标识）
     * @param maxRetries 最大重试次数
     * @param modelId    模型 ID
     * @param iteration  当前迭代轮次（循环模式 1..N，非循环为 0，HITL 恢复键用）
     * @param executionId 执行 ID（复合 sessionId = executionId:agentIndex，HITL 交互隔离用，Task-06）
     * @return Agent 完整输出
     */
    public String executeWithRetry(AgentDefinition agentDef, String input,
                                   SseEmitter emitter, int agentIndex,
                                   int maxRetries, String modelId, int iteration, String executionId) {
        // 业务含义：非 HITL 路径才构建 AgenticServices 代理（HITL 路径使用 HITLReActStream，无需代理）。
        // buildAgent 置于重试循环外：构建失败（如模型不存在）直接抛错不重试，与 P2 行为一致（AC-018）
        Object agent = agentDef.isHitlEnabled() ? null : agentFactory.buildAgent(agentDef);

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return executeStreaming(agent, agentDef, input, emitter, agentIndex, iteration, executionId);
            } catch (WorkflowHITLException e) {
                // 业务含义：HITL 暂停信号（检查点/askUser）不重试，直接上抛由协调层进入 WAITING_USER（AC-N02）
                throw e;
            } catch (WorkflowTimeoutException e) {
                // 业务含义：超时不重试（每次重试都会超时），直接上抛由协调层推送 TIMEOUT（AC-023）
                throw e;
            } catch (WorkflowCancelledException e) {
                // 业务含义：用户取消不重试，直接上抛由协调层推送 TERMINATED（AC-022）
                throw e;
            } catch (Exception e) {
                if (attempt < maxRetries) {
                    WorkflowEventPublisher.send(emitter, "step_retry", Map.of(
                            "agentIndex", agentIndex,
                            "retryCount", attempt + 1,
                            "remainingRetries", maxRetries - attempt - 1));
                } else {
                    WorkflowEventPublisher.send(emitter, "step_error", Map.of(
                            "agentIndex", agentIndex,
                            "error", e.getMessage(),
                            "retryCount", maxRetries));
                    // P3 变更：重试耗尽抛 WorkflowPausedException（协调层据此进入 PAUSED 而非 FAILED，
                    // 携带失败 Agent 名与步骤索引供前端高亮定位，AC-016；子类兼容既有 BusinessException 分支）
                    throw new WorkflowPausedException(agentDef.getName(), agentIndex,
                            "Agent 执行失败: " + agentDef.getName() + ": " + e.getMessage(), e);
                }
            }
        }
        throw new IllegalStateException("不应到达此处");
    }

    /**
     * 执行 Agent 并捕获流式输出
     * <p>
     * 业务含义：通过反射调用 Agent 接口的 @Agent 方法获取 TokenStream，
     * 注册 onPartialResponse（推送 token 事件，AC-009）、onCompleteResponse、
     * onError 回调，用 CompletableFuture 等待流式完成并返回完整文本。
     * 超时抛出 WorkflowTimeoutException（AC-023）。
     * 执行前检测 @HumanCheckpoint 注解：有注解则暂停等待人工确认（AC-N02/AC-T02）。
     * hitlEnabled=true 时改用 HITLReActStream（显式 ReAct + askUser 拦截，Task-06）。
     * </p>
     */
    String executeStreaming(Object agent, AgentDefinition agentDef,
                            String input, SseEmitter emitter, int agentIndex,
                            int iteration, String executionId) {
        Method executeMethod = findAgentMethod(agentDef);

        // 业务含义：模板预设检查点（@HumanCheckpoint）在方法执行前拦截——构造确认型 HITL 快照并抛异常，
        // 协调层（Task-07 handleHITLPaused）捕获后保存快照并进入 WAITING_USER 等待人工确认（AC-N02/AC-T02）
        checkHumanCheckpoint(agentDef, input, agentIndex, iteration, executeMethod);

        return doExecuteStreaming(agent, agentDef, input, emitter, agentIndex, iteration, executionId, executeMethod);
    }

    /**
     * HITL checkpoint 恢复执行（Task-08）：跳过 @HumanCheckpoint 检测直接执行方法
     * <p>
     * 业务含义：用户确认检查点（approved=true）后执行暂停的方法。与正常 executeStreaming 的
     * 区别仅在于不重复检测注解（否则会再次抛检查点异常导致死循环，AC-N02/AC-S01）。
     * hitlEnabled=true 仍走 HITLReActStream（保留 askUser 能力），false 走 TokenStream。
     * </p>
     */
    String executeStreamingResume(Object agent, AgentDefinition agentDef,
                                  String input, SseEmitter emitter, int agentIndex,
                                  int iteration, String executionId) {
        Method executeMethod = findAgentMethod(agentDef);
        return doExecuteStreaming(agent, agentDef, input, emitter, agentIndex, iteration, executionId, executeMethod);
    }

    /**
     * 反射查找 @Agent 方法（含缺失校验）
     */
    private Method findAgentMethod(AgentDefinition agentDef) {
        return Arrays.stream(agentDef.getInterfaceClass().getMethods())
                .filter(m -> m.isAnnotationPresent(Agent.class))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.WORKFLOW_EXECUTION_FAILED,
                        "Agent 接口未标注 @Agent 方法: " + agentDef.getInterfaceClass().getName()));
    }

    /**
     * 执行流式逻辑（hitlEnabled 分流 + TokenStream），不含 @HumanCheckpoint 检测
     * <p>
     * 业务含义：checkpoint 检测（executeStreaming）与恢复路径（executeStreamingResume）
     * 共用此方法——区分仅在于是否检测注解。
     * </p>
     */
    private String doExecuteStreaming(Object agent, AgentDefinition agentDef,
                                      String input, SseEmitter emitter, int agentIndex,
                                      int iteration, String executionId, Method executeMethod) {
        // 业务含义：hitlEnabled=true 时使用 HITLReActStream（显式 ReAct，Agent 可调用 askUser 暂停-恢复）；
        // false（默认）走现有 TokenStream 路径（零回归，决策 1）
        if (agentDef.isHitlEnabled()) {
            return executeHitlStreaming(agentDef, input, emitter, agentIndex, iteration, executionId);
        }

        final TokenStream stream;
        try {
            stream = (TokenStream) executeMethod.invoke(agent, input);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String detail = (cause.getMessage() != null) ? ": " + cause.getMessage() : "";
            // 业务含义：调用阶段失败也需携带底层根因（如模型未配置/LLM 调用错误），便于定位
            throw new BusinessException(ErrorCode.WORKFLOW_EXECUTION_FAILED,
                    "Agent 调用失败: " + agentDef.getName() + detail, cause);
        } catch (IllegalAccessException e) {
            throw new BusinessException(ErrorCode.WORKFLOW_EXECUTION_FAILED,
                    "Agent 方法不可访问: " + agentDef.getName(), e);
        }

        CompletableFuture<String> future = new CompletableFuture<>();

        // 业务含义：onPartialResponse 每次返回 token 片段，实时推送给客户端（AC-009）
        stream.onPartialResponse(token ->
                        WorkflowEventPublisher.send(emitter, "token", Map.of("agentIndex", agentIndex, "content", token)))
                .onCompleteResponse(response -> {
                    String fullResponse = (response.aiMessage() != null) ? response.aiMessage().text() : null;
                    future.complete(fullResponse != null ? fullResponse : "");
                })
                .onError(future::completeExceptionally)
                .start();

        try {
            // 等待流式完成，超时阈值由 agentTimeoutMinutes 决定（AC-023）
            return future.get(agentTimeoutMinutes, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            throw new WorkflowTimeoutException("Agent 执行超时: " + agentDef.getName(), e);
        } catch (ExecutionException e) {
            Throwable cause = (e.getCause() != null) ? e.getCause() : e;
            if (cause instanceof WorkflowCancelledException) {
                throw (WorkflowCancelledException) cause;
            }
            // 业务含义：错误信息必须携带底层根因（如"未配置 chat 模型"/"LLM 连接超时"），便于用户定位问题
            String detail = (cause.getMessage() != null) ? ": " + cause.getMessage() : "";
            throw new BusinessException(ErrorCode.WORKFLOW_EXECUTION_FAILED,
                    "Agent 执行异常: " + agentDef.getName() + detail, cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkflowCancelledException("Agent 执行被中断", e);
        }
    }

    /**
     * 执行 HITL 显式 ReAct 流（hitlEnabled=true 时的 Agent 执行路径，Task-06）
     * <p>
     * 业务含义：hitlEnabled=true 时使用 HITLReActStream（显式 ReAct）替代 TokenStream——
     * 从 AgentDefinition 构建 HITL 系统提示词 + 工具 JSON + 消息列表，Agent 推理过程中可调用
     * askUser 工具向用户提问。onAskUser 回调拦截：构造 askUser 模式 HITL 快照（消息列表 +
     * 提问数据 + 暂停步骤）并抛 WorkflowHITLException；协调层 handleHITLPaused（Task-07）
     * 捕获后统一推送 ask_user + workflow_waiting 事件并进入 WAITING_USER（AC-N01/AC-T01/AC-S02）。
     * 复合 sessionId（executionId:agentIndex）保证并行模式下不同 Agent 的交互状态隔离。
     * </p>
     *
     * @param agentDef   Agent 定义
     * @param input      该步骤输入
     * @param emitter    SSE 发射器
     * @param agentIndex 步骤索引（复合 sessionId 组成部分）
     * @param iteration  循环模式轮次
     * @param executionId 执行 ID（复合 sessionId 组成部分）
     * @return Agent 完整输出（未触发 HITL 暂停时的最终回答）
     */
    private String executeHitlStreaming(AgentDefinition agentDef, String input, SseEmitter emitter,
                                        int agentIndex, int iteration, String executionId) {
        // 1. 解析工具（AgentDefinition.toolIds）+ 补入 askUser 工具（HITL 能力前提，AC-T01）
        List<Object> tools = resolveHitlTools(agentDef);

        // 2. 构建消息列表：HITL 场景系统提示词（三段组合）+ 用户输入
        // 业务含义：三段组合 = 角色段（role）+ 任务场景段（app-xxx）+ 工具引导段（hitl-guidance，
        // 决策 5 方案 A）——恢复 Agent 专属任务描述（AC-N03），hitl-guidance 承载工具描述与
        // askUser 使用引导（{{tools}} 运行时替换）。hitl-guidance 缺失时降级为原两段（role + hitl
        // 场景），保证工具引导不丢失（与现状行为一致）。
        String guidance = promptTemplateLoader.loadScenarioTemplate(PromptTemplateLoader.SCENARIO_HITL_GUIDANCE);
        String systemPrompt;
        if (guidance != null) {
            systemPrompt = promptTemplateLoader.composeSystemPrompt(
                            agentDef.getRoleName(), agentDef.getScenarioName())
                    + "\n\n" + guidance.replace("{{tools}}", toolSchemaConverter.convertToDescriptionText(tools));
        } else {
            systemPrompt = promptTemplateLoader.composeSystemPrompt(
                            agentDef.getRoleName(), PromptTemplateLoader.SCENARIO_HITL)
                    .replace("{{tools}}", toolSchemaConverter.convertToDescriptionText(tools));
        }
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        messages.add(UserMessage.from(input));

        // 3. 创建 HITLReActStream 并等待完成（retryCount=0 首次执行）
        return awaitHitlStream(agentDef, messages, tools, emitter, agentIndex, iteration, input, executionId, 0);
    }

    /**
     * 解析 HITL Agent 工具集（AgentDefinition.toolIds + askUser 补入，AC-T01）
     * <p>
     * 业务含义：首次执行与 askUser 恢复共用同一工具解析管道——从模板 toolIds 解析工具
     * 并补入 askUser 工具（HITL 追问能力前提），保证两路径工具集一致（AC-M02）。
     * </p>
     */
    private List<Object> resolveHitlTools(AgentDefinition agentDef) {
        // 业务含义：HITL 路径具备暂停-恢复能力，走 ForStreaming（deny 剔除、ask 保留，
        // ask 由 tool_confirm 暂停-恢复机制接管，AC-N01/AC-S01 修复加载期绕过）
        List<Object> tools = (agentDef.getToolIds() == null || agentDef.getToolIds().isEmpty())
                ? List.of()
                : toolRegistry.resolveToolsForStreaming(agentDef.getToolIds());
        return sessionToolResolver.ensureAskUserTool(tools);
    }

    /**
     * 创建 HITLReActStream 并等待完成（首次执行与 askUser 恢复共用，Task-06/08）
     * <p>
     * 业务含义：统一的显式 ReAct 执行底座——工具 JSON 生成 + 模型获取 + 复合 sessionId +
     * HITLReActStream 创建（retryCount 由调用方决定：首次 0 / 恢复 retryCount+1）+ 回调注册
     * （onPartialResponse 推 token；onAskUser 拦截构造快照抛异常）+ future 等待。
     * 恢复场景传入含用户回复的消息列表与递增 retryCount（追问计数累计，AC-S02）。
     * </p>
     */
    private String awaitHitlStream(AgentDefinition agentDef, List<ChatMessage> messages,
                                   List<Object> tools, SseEmitter emitter, int agentIndex,
                                   int iteration, String input, String executionId, int retryCount) {
        String toolsJson = toolSchemaConverter.convertToJson(tools);

        // 获取思考流式模型（按 AgentDefinition.modelId，null 用默认）
        String modelId = agentDef.getModelId();
        ThinkingStreamingChatModel thinkingModel = (modelId != null)
                ? modelFactory.getThinkingStreamingChatModelByModelId(modelId)
                : modelFactory.getDefaultThinkingStreamingChatModel();

        // 复合 sessionId（executionId:agentIndex）——HumanInteractionManager 按 executionId+Agent 隔离（AC-E02）
        String sessionId = executionId + ":" + agentIndex;

        // 创建 HITLReActStream（显式 ReAct 循环 + askUser 拦截）
        HITLReActStream hitl = new HITLReActStream(
                thinkingModel, messages, toolsJson, toolExecutor, humanInteractionManager,
                sessionId, modelId, retryCount, agentConfig.getThinkingMaxIterations());

        CompletableFuture<String> future = new CompletableFuture<>();

        hitl.onPartialResponse(token ->
                        WorkflowEventPublisher.send(emitter, "token", Map.of("agentIndex", agentIndex, "content", token)))
                .onAskUser((type, question, options, r) -> {
                    // 业务含义：askUser 拦截——构造 askUser 模式 HITL 快照（消息列表 + 提问数据 + 暂停步骤），
                    // 抛 WorkflowHITLException。事件推送（ask_user + workflow_waiting）由协调层 handleHITLPaused
                    // 统一执行（Task-07），保证事件与 WAITING_USER 状态流转原子一致（AC-N01）
                    WorkflowHITLState.AskUserData askUserData =
                            new WorkflowHITLState.AskUserData(type, question, options, r);
                    WorkflowHITLState.PendingStep pendingStep =
                            new WorkflowHITLState.PendingStep(agentIndex, agentDef.getName(), input, iteration);
                    WorkflowHITLState hitlState = new WorkflowHITLState(
                            WorkflowHITLState.MODE_ASK_USER, askUserData, pendingStep, messages, r);
                    throw new WorkflowHITLException(hitlState, "Agent 等待用户输入: " + agentDef.getName(), null);
                })
                .onToolConfirm((toolCallId, toolName, toolDescription, arguments) -> {
                    // 业务含义：ask 级工具拦截——构造 toolConfirm 模式 HITL 快照（ReAct 消息列表 +
                    // 工具确认四要素 + 暂停步骤）并抛 WorkflowHITLException。事件推送（tool_confirm +
                    // workflow_waiting）由协调层 handleHITLPaused 统一执行（Task-15），与 onAskUser 同构，
                    // 保证事件与 WAITING_USER 状态流转原子一致（AC-H01/AC-H02/AC-M02）。
                    // retryCount 原值传递（确认不累计追问次数，区别于 askUser 的追问累计）
                    WorkflowHITLState.ToolConfirmData toolConfirmData =
                            new WorkflowHITLState.ToolConfirmData(toolCallId, toolName, toolDescription, arguments);
                    WorkflowHITLState.PendingStep pendingStep =
                            new WorkflowHITLState.PendingStep(agentIndex, agentDef.getName(), input, iteration);
                    WorkflowHITLState hitlState = new WorkflowHITLState(
                            WorkflowHITLState.MODE_TOOL_CONFIRM, toolConfirmData, pendingStep, messages, retryCount);
                    throw new WorkflowHITLException(hitlState, "Agent 等待工具确认: " + agentDef.getName(), null);
                })
                .onComplete(response -> future.complete(response != null ? response : ""))
                .onError(future::completeExceptionally)
                .start();

        try {
            return future.get(agentTimeoutMinutes, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            throw new WorkflowTimeoutException("Agent 执行超时: " + agentDef.getName(), e);
        } catch (ExecutionException e) {
            Throwable cause = (e.getCause() != null) ? e.getCause() : e;
            if (cause instanceof WorkflowHITLException) {
                // 业务含义：askUser 拦截信号透传（不包装为 BusinessException），协调层据此进入 WAITING_USER
                throw (WorkflowHITLException) cause;
            }
            if (cause instanceof WorkflowCancelledException) {
                throw (WorkflowCancelledException) cause;
            }
            // 业务含义：错误信息必须携带底层根因（如"未配置 chat 模型"/"LLM 连接超时"），便于用户定位问题
            String detail = (cause.getMessage() != null) ? ": " + cause.getMessage() : "";
            throw new BusinessException(ErrorCode.WORKFLOW_EXECUTION_FAILED,
                    "Agent 执行异常: " + agentDef.getName() + detail, cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkflowCancelledException("Agent 执行被中断", e);
        }
    }

    /**
     * HITL 恢复执行（Task-08）
     * <p>
     * 业务含义：策略重放时 HITL 暂停步以恢复方式执行（用户已回复/确认，避免重新触发 HITL 死循环）：
     * 1. askUser 模式：快照消息列表 + 用户回复 ToolExecutionResultMessage 追加 -> 新建
     *    HITLReActStream(retryCount+1) 续跑 ReAct 循环（AC-N03/AC-M02）；
     * 2. checkpoint 模式（approved=true）：正常执行暂停的 @Agent 方法（TokenStream，
     *    跳过注解检测，AC-N02/AC-S01）。
     * </p>
     *
     * @param agentDef   Agent 定义
     * @param hitlState  HITL 暂停快照（含消息列表/暂停步骤）
     * @param userReply  用户回复文本（askUser 模式注入 ReAct；checkpoint 模式为 null）
     * @param approved   检查点确认结果（checkpoint 模式 true=执行；askUser 模式为 null）
     * @param emitter    SSE 发射器
     * @param agentIndex 步骤索引（复合 sessionId 组成部分）
     * @param executionId 执行 ID（复合 sessionId 组成部分）
     * @return Agent 恢复执行后的输出
     */
    public String executeHitlResume(AgentDefinition agentDef, WorkflowHITLState hitlState,
                                    String userReply, Boolean approved, SseEmitter emitter,
                                    int agentIndex, String executionId) {
        if (WorkflowHITLState.MODE_ASK_USER.equals(hitlState.getHitlMode())) {
            return resumeAskUserStream(agentDef, hitlState, userReply, emitter, agentIndex, executionId);
        }
        if (WorkflowHITLState.MODE_TOOL_CONFIRM.equals(hitlState.getHitlMode())) {
            // 业务含义：ask 级工具确认暂停（AC-H01）——批准则执行工具，拒绝则回填脱敏文案，均续跑 ReAct
            return resumeToolConfirmStream(agentDef, hitlState, approved, emitter, agentIndex, executionId);
        }
        // 业务含义：checkpoint 模式确认（approved=true）——正常执行暂停的 @Agent 方法
        Object agent = agentFactory.buildAgent(agentDef);
        String input = hitlState.getPendingStep().getInput();
        int iteration = hitlState.getPendingStep().getIteration();
        return executeStreamingResume(agent, agentDef, input, emitter, agentIndex, iteration, executionId);
    }

    /**
     * askUser 模式恢复：快照消息 + 用户回复续跑 ReAct 循环（Task-08）
     * <p>
     * 业务含义：恢复时在暂停快照的消息列表（已含该轮 AiMessage 的 askUser 工具调用）基础上，
     * 追加用户回复 ToolExecutionResultMessage（id 匹配 askUser 工具调用），新建
     * HITLReActStream(retryCount+1) 续跑——与单 Agent HITL 恢复（UnifiedChatStream）同构。
     * 恢复中再次 askUser 则构造新快照（消息列表已含本次回复）抛异常，协调层更新快照（循环暂停-恢复）。
     * </p>
     */
    private String resumeAskUserStream(AgentDefinition agentDef, WorkflowHITLState hitlState,
                                       String userReply, SseEmitter emitter, int agentIndex,
                                       String executionId) {
        // 1. 复制快照消息列表并追加用户回复（id 匹配 askUser 工具调用，AC-M02 消息列表保持）
        List<ChatMessage> resumeMessages = new ArrayList<>(hitlState.getMessages());
        String toolCallId = findAskUserToolCallId(resumeMessages);
        resumeMessages.add(ToolExecutionResultMessage.from(toolCallId, "askUser", userReply));

        // 2. 复用统一执行底座（resolveHitlTools + awaitHitlStream，Task-08 重构）：
        //    retryCount+1 续跑（追问计数累计，AC-S02）；input/iteration 来自暂停步骤快照，
        //    awaitHitlStream 的 onAskUser 拦截会以传入消息列表自动构造新快照（循环暂停-恢复，AC-N03）
        return awaitHitlStream(agentDef, resumeMessages, resolveHitlTools(agentDef), emitter,
                agentIndex, hitlState.getPendingStep().getIteration(),
                hitlState.getPendingStep().getInput(), executionId, hitlState.getRetryCount() + 1);
    }

    /**
     * toolConfirm 模式恢复：批准后执行待确认工具并回填结果续跑 ReAct（Task-13）
     * <p>
     * 业务含义：ask 级工具被权限拦截暂停（AC-H01）后用户批准/拒绝恢复：
     * 1. 批准（approved=true）：直接经 ToolExecutor 执行待确认工具（参数原样，不重复解析），
     *    以暂停时 toolCallId 回填 ToolExecutionResultMessage 续跑 ReAct 循环（AC-N03/AC-M02）；
     * 2. 拒绝（approved=false）：不执行工具，回填固定脱敏拒绝文案（含"用户拒绝"语义，不含
     *    权限配置细节，AC-S04），LLM 据此调整方案，工作流不终止（区别于 checkpoint 拒绝终止，决策 7）。
     * 两分支均 awaitHitlStream 续跑且 retryCount 原值传递（确认不累计追问次数）。
     * </p>
     */
    private String resumeToolConfirmStream(AgentDefinition agentDef, WorkflowHITLState hitlState,
                                           Boolean approved, SseEmitter emitter, int agentIndex,
                                           String executionId) {
        // 1. 复制快照消息列表，以暂停时 toolCallId 回填工具执行结果/拒绝文案（AC-M02）
        List<ChatMessage> resumeMessages = new ArrayList<>(hitlState.getMessages());
        WorkflowHITLState.ToolConfirmData data = hitlState.getToolConfirmData();
        String resultText;
        if (Boolean.TRUE.equals(approved)) {
            // 业务含义：批准后直接执行待确认工具（参数原样），执行结果回填 LLM 会话上下文
            resultText = toolExecutor.execute(data.getToolName(), data.getArguments());
        } else {
            // 业务含义：拒绝后回填固定脱敏文案（AC-S04）——告知用户拒绝，引导调整方案，不终止工作流
            resultText = "用户拒绝执行工具 " + data.getToolName() + "，请调整方案或改用其他方式完成目标。";
        }
        resumeMessages.add(ToolExecutionResultMessage.from(data.getToolCallId(), data.getToolName(), resultText));

        // 2. 复用统一执行底座续跑 ReAct 循环：retryCount 原值传递（确认不累计追问次数，Task-13）
        return awaitHitlStream(agentDef, resumeMessages, resolveHitlTools(agentDef), emitter,
                agentIndex, hitlState.getPendingStep().getIteration(),
                hitlState.getPendingStep().getInput(), executionId, hitlState.getRetryCount());
    }

    /**
     * 从消息列表查找最后一个 askUser 工具调用的 ID（复用单 Agent 恢复逻辑，AC-M02）
     * <p>
     * 业务含义：恢复时需以暂停时 askUser 工具调用的 id 回填用户回复结果消息，
     * 保证 LLM 会话上下文一致（ToolExecutionResultMessage 需匹配 AiMessage 的 toolCall id）。
     * </p>
     */
    private String findAskUserToolCallId(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage msg = messages.get(i);
            if (msg instanceof AiMessage aiMsg && aiMsg.hasToolExecutionRequests()) {
                for (ToolExecutionRequest req : aiMsg.toolExecutionRequests()) {
                    if ("askUser".equals(req.name())) {
                        return req.id();
                    }
                }
            }
        }
        return "askUser";
    }

    /**
     * 检测 @HumanCheckpoint 注解，命中则抛 WorkflowHITLException（checkpoint 模式）
     * <p>
     * 业务含义：模板预设检查点在方法执行前拦截——构造确认型 askUser 数据（type=confirm）
     * 与暂停步骤位置（agentIndex/agentName/input/iteration）快照，封装进 WorkflowHITLException
     * 上抛。协调层捕获异常后从快照恢复（Task-08：确认后执行方法 / 拒绝后终止，AC-N02/AC-S01）。
     * </p>
     *
     * @param agentDef      Agent 定义
     * @param input         该步骤输入（恢复时重放）
     * @param agentIndex    步骤索引
     * @param iteration     循环模式轮次（非循环为 0）
     * @param executeMethod 待执行的 @Agent 方法（反射检测注解）
     */
    private void checkHumanCheckpoint(AgentDefinition agentDef, String input, int agentIndex,
                                      int iteration, Method executeMethod) {
        HumanCheckpoint checkpoint = executeMethod.getAnnotation(HumanCheckpoint.class);
        if (checkpoint == null) {
            return;
        }
        // 业务含义：确认型提问——携带确认/取消选项，用户确认后执行方法、拒绝后终止工作流
        WorkflowHITLState.AskUserData askUserData = new WorkflowHITLState.AskUserData(
                "confirm", checkpoint.message(), List.of("确认", "取消"), 0);
        WorkflowHITLState.PendingStep pendingStep = new WorkflowHITLState.PendingStep(
                agentIndex, agentDef.getName(), input, iteration);
        // 业务含义：checkpoint 模式在方法执行前暂停，无 ReAct 上下文，messages 为 null
        WorkflowHITLState hitlState = new WorkflowHITLState(
                WorkflowHITLState.MODE_CHECKPOINT, askUserData, pendingStep, null, 0);
        throw new WorkflowHITLException(hitlState,
                "检查点等待人工确认: " + agentDef.getName(), null);
    }

    /**
     * 检查取消标志，已取消则抛出 WorkflowCancelledException（AC-022）
     * <p>
     * 业务含义：所有执行策略在每步 Agent 执行前调用，用户终止后立即停止。
     * </p>
     *
     * @param cancelFlag 取消标志（null 视为未取消）
     */
    public static void checkCancelled(AtomicBoolean cancelFlag) {
        if (cancelFlag != null && cancelFlag.get()) {
            throw new WorkflowCancelledException();
        }
    }
}
