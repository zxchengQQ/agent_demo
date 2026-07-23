package com.agentdemo.agent.single;

import com.agentdemo.agent.core.ThinkingTokenStream;
import com.agentdemo.llm.factory.ArkThinkingStreamingChatModel;
import com.agentdemo.llm.factory.ThinkingStreamHandler;
import com.agentdemo.llm.factory.ToolCall;
import com.agentdemo.tools.registry.ToolExecutor;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * ReAct 思考流式令牌流实现（Task-07 新增）
 * <p>
 * 业务含义：实现显式 ReAct 循环（推理 -> 工具调用 -> 观察 -> 继续推理 -> 最终回答），
 * 通过方舟 LLM 原生驱动 ReAct，每轮同步调用 model.stream() 并解析响应回调。
 * </p>
 * <p>
 * 核心流程：
 * 1. 每轮调用 model.stream(messages, toolsJson, handler)，handler 实时回调 reasoning/thought
 * 2. 收到 finish_reason=tool_calls 时，推送 action 事件 + 执行工具 + 推送 observation 事件 + 回填消息
 * 3. 收到 finish_reason=stop 时，推送 final-answer 标记 + onComplete，退出循环
 * 4. 达到 maxIterations 时，不带 tools 强制让 LLM 生成总结性回答
 * </p>
 */
public class ReActThinkingStream implements ThinkingTokenStream {

    private static final Logger log = LoggerFactory.getLogger(ReActThinkingStream.class);

    private final ArkThinkingStreamingChatModel model;
    private final List<ChatMessage> messages;
    private final String toolsJson;
    private final ToolExecutor toolExecutor;
    private final int maxIterations;

    // 连续工具调用失败计数器（BUG 修复：连续失败时提前中断，避免空转浪费迭代）
    private static final int MAX_CONSECUTIVE_TOOL_FAILURES = 3;
    private int consecutiveToolFailures = 0;

    // 回调消费者
    private ThinkingConsumer thinkingConsumer;
    private ThoughtConsumer thoughtConsumer;
    private ActionConsumer actionConsumer;
    private ObservationConsumer observationConsumer;
    private FinalAnswerConsumer finalAnswerConsumer;
    private CompleteConsumer completeConsumer;
    private ErrorConsumer errorConsumer;

    public ReActThinkingStream(ArkThinkingStreamingChatModel model,
                               List<ChatMessage> messages,
                               String toolsJson,
                               ToolExecutor toolExecutor,
                               int maxIterations) {
        this.model = model;
        this.messages = messages;
        this.toolsJson = toolsJson;
        this.toolExecutor = toolExecutor;
        this.maxIterations = maxIterations;
    }

    @Override
    public ThinkingTokenStream onPartialThinking(ThinkingConsumer consumer) {
        this.thinkingConsumer = consumer;
        return this;
    }

    @Override
    public ThinkingTokenStream onPartialResponse(ResponseConsumer consumer) {
        // ReAct 模式不使用 responseConsumer，content 通过 onPartialThought 推送
        return this;
    }

    @Override
    public ThinkingTokenStream onComplete(CompleteConsumer consumer) {
        this.completeConsumer = consumer;
        return this;
    }

    @Override
    public ThinkingTokenStream onError(ErrorConsumer consumer) {
        this.errorConsumer = consumer;
        return this;
    }

    @Override
    public ThinkingTokenStream onPartialThought(ThoughtConsumer consumer) {
        this.thoughtConsumer = consumer;
        return this;
    }

    @Override
    public ThinkingTokenStream onAction(ActionConsumer consumer) {
        this.actionConsumer = consumer;
        return this;
    }

    @Override
    public ThinkingTokenStream onObservation(ObservationConsumer consumer) {
        this.observationConsumer = consumer;
        return this;
    }

    @Override
    public ThinkingTokenStream onFinalAnswer(FinalAnswerConsumer consumer) {
        this.finalAnswerConsumer = consumer;
        return this;
    }

    /**
     * 启动 ReAct 循环
     * 业务含义：同步执行多轮 ReAct 循环，每轮调用 LLM 并处理响应
     */
    @Override
    public void start() {
        try {
            runReActLoop();
        } catch (Exception e) {
            log.error("ReAct 循环异常", e);
            if (errorConsumer != null) {
                errorConsumer.accept(e);
            }
        }
    }

    /**
     * ReAct 循环核心逻辑
     * <p>
     * 业务含义：循环调用 LLM，根据 finish_reason 决定继续还是终止。
     * finish_reason=tool_calls -> 执行工具，回填消息，继续循环
     * finish_reason=stop -> 推送 final-answer，退出循环
     * iteration > maxIterations -> 不带 tools 强制总结
     * </p>
     */
    private void runReActLoop() {
        int iteration = 0;
        boolean shouldContinue = true;
        String finalResponse = "";

        while (shouldContinue && iteration < maxIterations) {
            iteration++;
            final int currentIteration = iteration;

            // 每轮状态收集
            IterationResult result = new IterationResult();

            ThinkingStreamHandler handler = createHandler(result, currentIteration);

            // 业务含义：同步调用 LLM（model.stream 内部会阻塞直到 SSE 流读取完毕）
            model.stream(messages, toolsJson, handler);

            // 检查错误
            if (result.error != null) {
                if (errorConsumer != null) {
                    errorConsumer.accept(result.error);
                }
                return;
            }

            // 根据 finish_reason 决定下一步
            if ("stop".equals(result.finishReason)) {
                // 业务含义：LLM 给出最终回答，推送 final-answer 标记（AC-006）
                finalResponse = result.content.toString();
                if (finalAnswerConsumer != null) {
                    finalAnswerConsumer.accept(currentIteration);
                }
                shouldContinue = false;
            } else if ("tool_calls".equals(result.finishReason)) {
                // 业务含义：LLM 决定调用工具，执行工具调用并回填消息（AC-004, AC-005）
                boolean allSuccess = executeToolCalls(result.toolCalls, currentIteration);
                if (!allSuccess) {
                    consecutiveToolFailures++;
                    if (consecutiveToolFailures >= MAX_CONSECUTIVE_TOOL_FAILURES) {
                        log.warn("连续 {} 轮工具调用失败，提前中断 ReAct 循环", consecutiveToolFailures);
                        shouldContinue = false;
                    }
                } else {
                    consecutiveToolFailures = 0;
                }
            }
        }

        // 业务含义：达到 maxIterations 仍未得出最终回答，强制总结（AC-011）
        if (shouldContinue) {
            iteration++;
            final int currentIteration = iteration;
            IterationResult result = new IterationResult();
            ThinkingStreamHandler handler = createHandler(result, currentIteration);

            // 不带 tools 参数调用 LLM，强制生成总结性回答
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

        // 业务含义：推送 onComplete，携带最终回答（AC-006）
        if (completeConsumer != null) {
            completeConsumer.accept(finalResponse);
        }
    }

    /**
     * 创建 ThinkingStreamHandler，将回调桥接到消费者并收集迭代状态
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
                // 业务含义：content 实时推送为 thought（AC-003），区别于 onPartialThinking（方舟原生 reasoning_content）
                if (thoughtConsumer != null) {
                    thoughtConsumer.accept(token, iteration);
                }
                result.content.append(token);
            }

            @Override
            public void onToolCalls(List<ToolCall> toolCalls) {
                result.toolCalls.addAll(toolCalls);
            }

            @Override
            public void onComplete(String fullResponse, String finishReason) {
                result.finishReason = finishReason;
            }

            @Override
            public void onError(Throwable error) {
                result.error = error;
            }
        };
    }

    /**
     * 执行工具调用（串行执行，AC-022）
     * <p>
     * 业务含义：遍历 toolCalls，逐个执行工具，推送 action/observation 事件，
     * 并回填 assistant 消息（含 tool_calls）和 tool 结果消息到消息列表。
     * 工具失败时将错误信息作为 Observation 返回，不中断循环（AC-012）。
     * </p>
     */
    private boolean executeToolCalls(List<ToolCall> toolCalls, int iteration) {
        // 回填 assistant 消息（含 toolExecutionRequests），供下一轮 LLM 理解上下文
        List<ToolExecutionRequest> requests = toolCalls.stream()
                .map(tc -> ToolExecutionRequest.builder()
                        .id(tc.getId())
                        .name(tc.getFunctionName())
                        .arguments(tc.getArguments())
                        .build())
                .toList();
        messages.add(AiMessage.aiMessage("", requests));

        // 串行执行每个工具调用（AC-022：不并行）
        boolean allSuccess = true;
        for (ToolCall tc : toolCalls) {
            // 推送 action 事件（AC-004）
            if (actionConsumer != null) {
                actionConsumer.accept(tc.getFunctionName(), tc.getArguments(), iteration);
            }

            // 执行工具（AC-005），失败时返回错误字符串不抛异常（AC-012）
            String toolResult = toolExecutor.execute(tc.getFunctionName(), tc.getArguments());

            // 业务含义：检查工具是否执行失败（BUG 修复：连续失败时提前中断）
            if (toolResult.startsWith("工具不存在") || toolResult.startsWith("工具执行失败")) {
                allSuccess = false;
            }

            // 推送 observation 事件（AC-005）
            if (observationConsumer != null) {
                observationConsumer.accept(toolResult, iteration);
            }

            // 回填 tool 结果消息，供下一轮 LLM 获取工具执行结果
            messages.add(ToolExecutionResultMessage.from(tc.getId(), tc.getFunctionName(), toolResult));
        }
        return allSuccess;
    }

    /**
     * 单轮迭代状态收集（用于在 handler 回调和循环主逻辑之间传递状态）
     */
    private static class IterationResult {
        String finishReason;
        final List<ToolCall> toolCalls = new ArrayList<>();
        final StringBuilder content = new StringBuilder();
        Throwable error;
    }
}
