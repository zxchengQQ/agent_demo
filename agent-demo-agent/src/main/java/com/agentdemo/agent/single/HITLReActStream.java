package com.agentdemo.agent.single;

import com.agentdemo.agent.core.HitlTokenStream;
import com.agentdemo.agent.core.HumanInteractionManager;
import com.agentdemo.agent.core.ThinkingTokenStream;
import com.agentdemo.llm.thinking.ThinkingStreamHandler;
import com.agentdemo.llm.thinking.ThinkingStreamingChatModel;
import com.agentdemo.llm.thinking.ToolCall;
import com.agentdemo.tools.registry.ToolExecutor;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
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
 * 业务含义：在 ReActThinkingStream 基础上增加 askUser 工具拦截能力。
 * 当 LLM 调用 askUser 工具时，不执行工具方法，而是保存当前 ReAct 上下文到 HumanInteractionManager，
 * 触发 onAskUser 回调，暂停 ReAct 循环。用户回复后由 AgentController 创建新实例恢复执行。
 * </p>
 * <p>
 * 核心流程：
 * 1. 每轮调用 model.stream(messages, toolsJson, handler)，handler 实时回调 reasoning/thought
 * 2. 收到 finish_reason=tool_calls 时，检查工具名是否为 askUser
 * 3. 若为 askUser：解析参数、保存状态、触发 onAskUser、暂停循环
 * 4. 若为其他工具：正常执行（同 ReActThinkingStream）
 * 5. 收到 finish_reason=stop 时，推送 final-answer + onComplete，退出循环
 * </p>
 */
public class HITLReActStream implements HitlTokenStream {

    private static final Logger log = LoggerFactory.getLogger(HITLReActStream.class);

    /** askUser 工具名（用于拦截判断） */
    private static final String ASK_USER_TOOL_NAME = "askUser";

    /** 最大追问次数（超过后返回错误 Observation） */
    private static final int MAX_RETRY_COUNT = 3;

    private final ThinkingStreamingChatModel model;
    private final List<ChatMessage> messages;
    private final String toolsJson;
    private final ToolExecutor toolExecutor;
    private final HumanInteractionManager humanInteractionManager;
    private final String sessionId;
    private final String modelId;
    private final int retryCount;
    private final int maxIterations;

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

    public HITLReActStream(ThinkingStreamingChatModel model,
                           List<ChatMessage> messages,
                           String toolsJson,
                           ToolExecutor toolExecutor,
                           HumanInteractionManager humanInteractionManager,
                           String sessionId,
                           String modelId,
                           int retryCount,
                           int maxIterations) {
        this.model = model;
        this.messages = messages;
        this.toolsJson = toolsJson;
        this.toolExecutor = toolExecutor;
        this.humanInteractionManager = humanInteractionManager;
        this.sessionId = sessionId;
        this.modelId = modelId;
        this.retryCount = retryCount;
        this.maxIterations = maxIterations;
    }

    // ==================== 回调注册方法 ====================

    @Override
    public HitlTokenStream onAskUser(AskUserConsumer consumer) {
        this.askUserConsumer = consumer;
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
            log.error("HITL ReAct 循环异常", e);
            if (errorConsumer != null) {
                errorConsumer.accept(e);
            }
        }
    }

    /**
     * ReAct 循环核心逻辑
     * <p>
     * 业务含义：与 ReActThinkingStream 相同的 ReAct 循环，但在工具执行阶段
     * 增加 askUser 拦截逻辑。askUser 被拦截时保存状态并暂停循环。
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
     * 执行工具调用，增加 askUser 拦截逻辑
     * <p>
     * 业务含义：遍历 toolCalls，若工具名为 askUser 则拦截（保存状态 + 触发回调 + 暂停），
     * 否则正常执行（同 ReActThinkingStream）。
     * </p>
     *
     * @return true 表示已暂停（askUser 被拦截），false 表示正常执行完毕
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
                // 业务含义：拦截 askUser 调用，不执行工具方法
                return handleAskUser(tc, iteration);
            }

            // 正常执行工具（同 ReActThinkingStream）
            String toolResult = toolExecutor.execute(tc.getFunctionName(), tc.getArguments());

            if (observationConsumer != null) {
                observationConsumer.accept(toolResult, iteration);
            }

            messages.add(ToolExecutionResultMessage.from(tc.getId(), tc.getFunctionName(), toolResult));
        }
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
}
