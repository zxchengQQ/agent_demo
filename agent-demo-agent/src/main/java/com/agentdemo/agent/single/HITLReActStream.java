package com.agentdemo.agent.single;

import com.agentdemo.agent.core.HitlTokenStream;
import com.agentdemo.agent.core.HumanInteractionManager;
import com.agentdemo.agent.core.ThinkingTokenStream;
import com.agentdemo.llm.thinking.ThinkingStreamHandler;
import com.agentdemo.llm.thinking.ThinkingStreamingChatModel;
import com.agentdemo.llm.thinking.ToolCall;
import com.agentdemo.tools.permission.ToolPermissionLevel;
import com.agentdemo.tools.registry.ToolExecutor;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.output.TokenUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * HITL ReAct 流式令牌流实现
 * <p>
 * 业务含义：显式 ReAct 循环基础上增加 askUser 工具拦截能力。
 * 当 LLM 调用 askUser 工具时，不执行工具方法，而是保存当前 ReAct 上下文到 HumanInteractionManager，
 * 触发 onAskUser 回调，暂停 ReAct 循环。用户回复后由 AgentController 创建新实例恢复执行。
 * </p>
 * <p>
 * 核心流程：
 * 1. 每轮调用 model.stream(messages, toolsJson, handler)，handler 实时回调 reasoning/thought
 * 2. 收到 finish_reason=tool_calls 时，检查工具名是否为 askUser
 * 3. 若为 askUser：解析参数、保存状态、触发 onAskUser、暂停循环
 * 4. 若为其他工具：正常执行
 * 5. 收到 finish_reason=stop 时，推送 final-answer + onComplete，退出循环
 * </p>
 */
public class HITLReActStream implements HitlTokenStream {

    private static final Logger log = LoggerFactory.getLogger(HITLReActStream.class);

    /** askUser 工具名（用于拦截判断） */
    private static final String ASK_USER_TOOL_NAME = "askUser";

    /** loadSkill 工具名（用于拦截判断，agent-skill 决策 4） */
    private static final String LOAD_SKILL_TOOL_NAME = "loadSkill";

    /** 最大追问次数（超过后返回错误 Observation） */
    private static final int MAX_RETRY_COUNT = 3;

    private final ThinkingStreamingChatModel model;
    private final List<ChatMessage> messages;
    /** toolsJson（可变：loadSkill 激活后热刷新绑定工具，技术方案 3.2） */
    private String toolsJson;
    private final ToolExecutor toolExecutor;
    private final HumanInteractionManager humanInteractionManager;
    private final String sessionId;
    private final String modelId;
    private final int retryCount;
    private final int maxIterations;

    /** 技能拦截器（可选：null = 无技能拦截/热刷新，app 模块等场景与现有行为一致） */
    private final SkillToolInterceptor skillInterceptor;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile boolean cancelled = false;

    // 回调消费者
    private ThinkingConsumer thinkingConsumer;
    private ThoughtConsumer thoughtConsumer;
    private ResponseConsumer responseConsumer;
    private ActionConsumer actionConsumer;
    private ObservationConsumer observationConsumer;
    private FinalAnswerConsumer finalAnswerConsumer;
    private CompleteConsumer completeConsumer;
    private ErrorConsumer errorConsumer;
    private AskUserConsumer askUserConsumer;
    private ToolConfirmConsumer toolConfirmConsumer;
    private SkillActivatedConsumer skillActivatedConsumer;

    /**
     * 构造器（兼容：无技能拦截器，与现有行为一致）
     */
    public HITLReActStream(ThinkingStreamingChatModel model,
                           List<ChatMessage> messages,
                           String toolsJson,
                           ToolExecutor toolExecutor,
                           HumanInteractionManager humanInteractionManager,
                           String sessionId,
                           String modelId,
                           int retryCount,
                           int maxIterations) {
        this(model, messages, toolsJson, toolExecutor, humanInteractionManager,
                sessionId, modelId, retryCount, maxIterations, null);
    }

    /**
     * 构造器（技能拦截器版本，agent-skill 决策 4）
     *
     * @param skillInterceptor 技能拦截器（null 则跳过 loadSkill 拦截与热刷新）
     */
    public HITLReActStream(ThinkingStreamingChatModel model,
                           List<ChatMessage> messages,
                           String toolsJson,
                           ToolExecutor toolExecutor,
                           HumanInteractionManager humanInteractionManager,
                           String sessionId,
                           String modelId,
                           int retryCount,
                           int maxIterations,
                           SkillToolInterceptor skillInterceptor) {
        this.model = model;
        this.messages = messages;
        this.toolsJson = toolsJson;
        this.toolExecutor = toolExecutor;
        this.humanInteractionManager = humanInteractionManager;
        this.sessionId = sessionId;
        this.modelId = modelId;
        this.retryCount = retryCount;
        this.maxIterations = maxIterations;
        this.skillInterceptor = skillInterceptor;
    }

    // ==================== 回调注册方法 ====================

    @Override
    public HitlTokenStream onAskUser(AskUserConsumer consumer) {
        this.askUserConsumer = consumer;
        return this;
    }

    @Override
    public HitlTokenStream onToolConfirm(ToolConfirmConsumer consumer) {
        this.toolConfirmConsumer = consumer;
        return this;
    }

    @Override
    public HitlTokenStream onSkillActivated(SkillActivatedConsumer consumer) {
        this.skillActivatedConsumer = consumer;
        return this;
    }

    @Override
    public HitlTokenStream onPartialThinking(ThinkingConsumer consumer) {
        this.thinkingConsumer = consumer;
        return this;
    }

    @Override
    public HitlTokenStream onPartialResponse(ResponseConsumer consumer) {
        this.responseConsumer = consumer;
        return this;
    }

    @Override
    public HitlTokenStream onComplete(CompleteConsumer consumer) {
        this.completeConsumer = consumer;
        return this;
    }

    @Override
    public HitlTokenStream onError(ErrorConsumer consumer) {
        this.errorConsumer = consumer;
        return this;
    }

    @Override
    public HitlTokenStream onPartialThought(ThoughtConsumer consumer) {
        this.thoughtConsumer = consumer;
        return this;
    }

    @Override
    public HitlTokenStream onAction(ActionConsumer consumer) {
        this.actionConsumer = consumer;
        return this;
    }

    @Override
    public HitlTokenStream onObservation(ObservationConsumer consumer) {
        this.observationConsumer = consumer;
        return this;
    }

    @Override
    public HitlTokenStream onFinalAnswer(FinalAnswerConsumer consumer) {
        this.finalAnswerConsumer = consumer;
        return this;
    }

    @Override
    public void cancel() {
        cancelled = true;
    }

    // ==================== ReAct 循环核心逻辑 ====================

    @Override
    public void start() {
        try {
            runReActLoop();
        } catch (Exception e) {
            // 业务含义：区分"协调层 HITL 暂停信号"与"真实运行异常"——
            // WorkflowHITLException（位于 agent-demo-app 模块）是工作流 tool_confirm/checkpoint/askUser
            // 暂停的控制流信号，由协调层捕获进入 WAITING_USER，属正常业务暂停而非运行错误，
            // 故降级为 DEBUG 记录，避免 ERROR 误导排查（2026-08-26 优化日志观感）。
            // 用全限定类名判断而非 instanceof（agent 模块依赖方向相反，不可见 app 模块类型）。
            if ("com.agentdemo.app.service.WorkflowHITLException".equals(e.getClass().getName())) {
                log.debug("HITL ReAct 循环收到暂停信号（由协调层处理）: {}", e.getMessage());
            } else {
                log.error("HITL ReAct 循环异常", e);
            }
            if (errorConsumer != null) {
                errorConsumer.accept(e);
            }
        }
    }

    /**
     * ReAct 循环核心逻辑
     * <p>
     * 业务含义：显式 ReAct 循环，在工具执行阶段增加 askUser 拦截逻辑。
     * askUser 被拦截时保存状态并暂停循环。
     * </p>
     */
    private void runReActLoop() {
        int iteration = 0;
        boolean shouldContinue = true;
        String finalResponse = "";

        while (shouldContinue && iteration < maxIterations) {
            if (cancelled) {
                log.info("HITL ReAct 循环已取消: iteration={}", iteration);
                return;
            }
            iteration++;
            final int currentIteration = iteration;

            IterationResult result = new IterationResult();
            ThinkingStreamHandler handler = createHandler(result, currentIteration);

            model.stream(messages, toolsJson, handler);

            if (result.error != null) {
                if (errorConsumer != null) {
                    errorConsumer.accept(result.error);
                }
                return;
            }

            if ("stop".equals(result.finishReason)) {
                // 业务含义：LLM 给出最终回答
                finalResponse = result.content.toString();
                if (finalAnswerConsumer != null) {
                    finalAnswerConsumer.accept(currentIteration);
                }
                shouldContinue = false;
            } else if ("tool_calls".equals(result.finishReason)) {
                // 业务含义：LLM 决定调用工具，检查是否为 askUser
                boolean paused = executeToolCalls(result.toolCalls, currentIteration);
                if (paused) {
                    // 业务含义：askUser 被拦截，暂停循环，不调用 onComplete
                    log.info("HITL ReAct 循环暂停: sessionId={}, retryCount={}", sessionId, retryCount);
                    return;
                }
            }
        }

        // 达到 maxIterations 强制总结
        if (shouldContinue) {
            if (cancelled) {
                return;
            }
            iteration++;
            final int currentIteration = iteration;
            IterationResult result = new IterationResult();
            ThinkingStreamHandler handler = createHandler(result, currentIteration);

            // agent-context-engineering（AC-N02/E02）：强制总结前注入收尾状态消息——
            // 读数 + 操作策略成对给出，模型据此立即收尾，避免继续尝试调用已移除的工具。
            messages.add(buildWrapUpStatusMessage());
            model.stream(messages, null, handler);

            if (result.error != null) {
                if (errorConsumer != null) {
                    errorConsumer.accept(result.error);
                }
                return;
            }

            finalResponse = result.content.toString();
            if (finalAnswerConsumer != null) {
                finalAnswerConsumer.accept(currentIteration);
            }
        }

        if (completeConsumer != null) {
            completeConsumer.accept(finalResponse);
        }
    }

    /**
     * 执行工具调用，增加 askUser 拦截与权限分级拦截逻辑
     * <p>
     * 业务含义：遍历 toolCalls，按序处理：
     * 1. askUser 工具豁免拦截（权限恒 ALLOW，AC-S03），先于权限检查保留现有行为；
     * 2. 其余工具先 checkPermission 裁决——ask 级拦截暂停（AC-N03）、deny 级防御兜底（AC-S01）、
     *    allow 级正常执行（AC-N02，现有行为零变更）；
     * 3. 多 toolCall 混合场景：遇到首个 ask 级 toolCall 即暂停，此前已执行的 allow 工具结果
     *    已在 messages 中（游标完整性），恢复后由 UnifiedChatStream 续跑。
     * </p>
     *
     * @return true 表示已暂停（askUser/ask 级工具被拦截），false 表示正常执行完毕
     */
    private boolean executeToolCalls(List<ToolCall> toolCalls, int iteration) {
        // 回填 assistant 消息（含 toolExecutionRequests）
        List<ToolExecutionRequest> requests = toolCalls.stream()
                .map(tc -> ToolExecutionRequest.builder()
                        .id(tc.getId())
                        .name(tc.getFunctionName())
                        .arguments(tc.getArguments())
                        .build())
                .toList();
        messages.add(AiMessage.aiMessage("", requests));

        for (ToolCall tc : toolCalls) {
            // 推送 action 事件
            if (actionConsumer != null) {
                actionConsumer.accept(tc.getFunctionName(), tc.getArguments(), iteration);
            }

            if (ASK_USER_TOOL_NAME.equals(tc.getFunctionName())) {
                // 业务含义：拦截 askUser 调用，不执行工具方法（豁免工具，权限恒 ALLOW）
                return handleAskUser(tc, iteration);
            }

            if (LOAD_SKILL_TOOL_NAME.equals(tc.getFunctionName())) {
                // 业务含义：拦截 loadSkill 调用（agent-skill 决策 4）——激活技能 + 热刷新绑定工具 + 事件回调，
                // 不暂停循环（区别于 askUser/tool_confirm）。拦截器为 null 时按普通工具执行。
                if (skillInterceptor != null) {
                    // handleLoadSkill 恒返回 false（不暂停），回填观察值后继续处理
                    handleLoadSkill(tc, iteration);
                    continue;
                }
            }

            // 业务含义：执行期权限裁决（AC-N03）——askUser 之外的每个 toolCall 先检查权限再执行
            ToolExecutor.ToolPermissionCheck check = toolExecutor.checkPermission(tc.getFunctionName());

            if (check.level() == ToolPermissionLevel.ASK) {
                // 业务含义：ask 级工具拦截暂停——保存现场、触发确认回调、暂停循环，
                // 不添加 ToolExecutionResultMessage（待用户批准后由 UnifiedChatStream 回填）
                return handleToolConfirm(tc, check, iteration);
            }

            if (check.level() == ToolPermissionLevel.DENY) {
                // 业务含义：deny 级防御兜底——正常路径加载期已过滤（AC-T01，LLM 不可见），
                // 此处为纵深防御第二道防线（AC-S01），记录安全事件日志；
                // 拒绝文案由 ToolExecutor.execute 内置兜底返回（方法体零触发）
                log.warn("工具权限 deny 兜底拦截: toolName={}, 调用来源=ReAct 工具循环", tc.getFunctionName());
            }

            // 正常执行路径：
            // - ALLOW：直接执行（AC-N02，现有行为零变更）
            // - DENY：execute 入口兜底返回拒绝文案，不执行方法体
            String toolResult = toolExecutor.execute(tc.getFunctionName(), tc.getArguments());

            if (observationConsumer != null) {
                observationConsumer.accept(toolResult, iteration);
            }

            messages.add(ToolExecutionResultMessage.from(tc.getId(), tc.getFunctionName(), toolResult));
        }
        return false;
    }

    /**
     * 处理 loadSkill 工具调用（agent-skill 决策 4：激活 + 热刷新，不暂停）
     * <p>
     * 业务含义：委托 SkillToolInterceptor 完成激活与工具热刷新，回填观察值并继续循环。
     * 激活成功时：触发 onSkillActivated 回调（SSE skill_activated 事件）、刷新 toolsJson
     * （下一迭代 LLM 即可调用绑定工具，AC-T02）。失败时：回填引导观察值，循环继续。
     * </p>
     *
     * @param tc        loadSkill toolCall
     * @param iteration 当前迭代
     * @return true=暂停（本实现不暂停，恒 false），false=继续
     */
    private boolean handleLoadSkill(ToolCall tc, int iteration) {
        String skillName = "";
        try {
            JsonNode args = objectMapper.readTree(tc.getArguments());
            if (args.has("skillName")) {
                skillName = args.get("skillName").asText();
            }
        } catch (Exception e) {
            log.warn("解析 loadSkill 参数失败: {}", e.getMessage());
        }

        SkillToolInterceptor.SkillInterceptionResult result =
                skillInterceptor.interceptLoadSkill(sessionId, skillName, toolsJson, iteration);

        // 激活成功 → 触发事件回调 + 热刷新 toolsJson
        if (result.activated()) {
            if (skillActivatedConsumer != null) {
                skillActivatedConsumer.accept(result.skillId(), result.skillName(),
                        result.source(), result.boundToolIds());
            }
            if (result.refreshedToolsJson() != null) {
                toolsJson = result.refreshedToolsJson();
                log.info("loadSkill 激活后热刷新工具集: sessionId={}, skillId={}", sessionId, result.skillId());
            }
        }

        // 回填观察值（激活成功=指令要点；失败=引导）
        if (observationConsumer != null) {
            observationConsumer.accept(result.observation(), iteration);
        }
        messages.add(ToolExecutionResultMessage.from(tc.getId(), LOAD_SKILL_TOOL_NAME, result.observation()));
        return false;
    }

    /**
     * 处理 askUser 工具调用
     * <p>
     * 业务含义：解析 askUser 参数，检查追问次数上限，保存状态到 HumanInteractionManager，
     * 触发 onAskUser 回调。保存的状态包含完整消息列表，用户回复后添加 Observation 恢复循环。
     * </p>
     *
     * @return true 表示已暂停
     */
    private boolean handleAskUser(ToolCall tc, int iteration) {
        // 解析 askUser 参数
        String askUserType = "text";
        String question = "";
        List<String> options = null;

        try {
            JsonNode args = objectMapper.readTree(tc.getArguments());
            if (args.has("type")) {
                askUserType = args.get("type").asText();
            }
            if (args.has("question")) {
                question = args.get("question").asText();
            }
            if (args.has("options") && args.get("options").isArray()) {
                options = new ArrayList<>();
                for (JsonNode opt : args.get("options")) {
                    options.add(opt.asText());
                }
            }
        } catch (Exception e) {
            log.warn("解析 askUser 参数失败，使用默认值: {}", e.getMessage());
        }

        // 业务含义：检查追问次数上限（AC-S01）
        if (retryCount >= MAX_RETRY_COUNT) {
            String errorObs = "已达最大追问次数（" + MAX_RETRY_COUNT + "次），请终止当前任务并告知用户。";
            if (observationConsumer != null) {
                observationConsumer.accept(errorObs, iteration);
            }
            messages.add(ToolExecutionResultMessage.from(tc.getId(), ASK_USER_TOOL_NAME, errorObs));
            return false;
        }

        // 保存暂停状态到 HumanInteractionManager
        humanInteractionManager.saveInteraction(
                sessionId, messages, askUserType, question, options,
                retryCount, modelId, toolsJson);

        // 触发 onAskUser 回调
        if (askUserConsumer != null) {
            askUserConsumer.accept(askUserType, question, options, retryCount);
        }

        // 业务含义：暂停循环，不添加 ToolExecutionResultMessage（等待用户回复后添加）
        return true;
    }

    /**
     * 处理 ask 级工具权限确认
     * <p>
     * 业务含义（决策 2 方案 A：快照职责外移）：ask 级工具调用被拦截时，本类**不保存快照**，
     * 仅触发 4 参 onToolConfirm 回调（含 toolCallId）后暂停 ReAct 循环（AC-N03）。
     * 快照持久化由宿主决定——单 Agent 宿主（UnifiedChatStream.registerHitlCallbacks）
     * 与工作流宿主（AgentExecutor.awaitHitlStream）各自按既有上下文补保存（AC-M02）。
     * 不添加 ToolExecutionResultMessage，恢复时由宿主按 toolCallId 回填结果消息。
     * </p>
     *
     * @param tc    被拦截的 toolCall
     * @param check 权限检查结果（含 toolId 与工具描述，描述用于确认卡片展示）
     * @return true 表示已暂停（等待用户批准/拒绝）
     */
    private boolean handleToolConfirm(ToolCall tc, ToolExecutor.ToolPermissionCheck check, int iteration) {
        // 业务含义：仅触发 4 参回调（toolCallId/工具名/描述/参数），快照由宿主补保存
        if (toolConfirmConsumer != null) {
            toolConfirmConsumer.accept(tc.getId(), tc.getFunctionName(), check.toolDescription(), tc.getArguments());
        }

        // 业务含义：暂停循环，不添加 ToolExecutionResultMessage（等待用户批准/拒绝后由恢复流程回填）
        return true;
    }

    /**
     * 创建 ThinkingStreamHandler
     */
    private ThinkingStreamHandler createHandler(IterationResult result, int iteration) {
        return new ThinkingStreamHandler() {
            @Override
            public void onPartialThinking(String thinking) {
                if (thinkingConsumer != null) {
                    thinkingConsumer.accept(thinking);
                }
            }

            @Override
            public void onPartialResponse(String token) {
                if (thoughtConsumer != null) {
                    thoughtConsumer.accept(token, iteration);
                }
                result.content.append(token);
                result.roundTokens.add(token);
            }

            @Override
            public void onToolCalls(List<ToolCall> toolCalls) {
                result.toolCalls.addAll(toolCalls);
            }

            @Override
            public void onComplete(String fullResponse, String finishReason, TokenUsage tokenUsage) {
                result.finishReason = finishReason;
                if ("stop".equals(finishReason) && responseConsumer != null) {
                    for (String token : result.roundTokens) {
                        responseConsumer.accept(token);
                    }
                }
            }

            @Override
            public void onError(Throwable error) {
                result.error = error;
            }
        };
    }

    /**
     * 单轮迭代状态收集
     */
    private static class IterationResult {
        String finishReason;
        final List<ToolCall> toolCalls = new ArrayList<>();
        final StringBuilder content = new StringBuilder();
        final List<String> roundTokens = new ArrayList<>();
        Throwable error;
    }

    /**
     * 末轮收尾状态消息（agent-context-engineering，AC-N02/E02/H02）
     * <p>
     * 业务含义：以 user 角色 + <agent_status> 标签包裹，读数（迭代 N/M）与收尾操作策略成对给出，
     * 追加到循环消息末尾（紧邻生成位置，注意力最高）。纯框架代码注入，内容不来自工具/用户输入
     * （AC-S01 可信源；AC-H02 不冒充用户指令——标签显式标识来源）。
     * </p>
     */
    private UserMessage buildWrapUpStatusMessage() {
        String text = "<agent_status>\n"
                + "已达最大迭代次数(" + maxIterations + "/" + maxIterations + ")。\n"
                + "操作策略：不要再发起工具调用，立即基于已收集的信息组织最终回答；"
                + "若信息不足以完整回答，如实说明已完成的部分与缺失的部分。\n"
                + "</agent_status>";
        return UserMessage.from(text);
    }
}
