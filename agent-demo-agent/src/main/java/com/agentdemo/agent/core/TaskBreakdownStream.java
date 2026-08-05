package com.agentdemo.agent.core;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.llm.thinking.ThinkingStreamingChatModel;
import com.agentdemo.llm.registry.ModelFactory;
import com.agentdemo.llm.thinking.ThinkingStreamHandler;
import com.agentdemo.llm.thinking.ToolCall;
import com.agentdemo.memory.shortterm.ChatMemoryManager;
import com.agentdemo.tools.registry.ToolExecutor;
import com.agentdemo.tools.registry.ToolSchemaConverter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 任务拆解三阶段编排流（CR-002 新增）
 * <p>
 * 业务含义：在单次 SSE 连接中完成三阶段编排：
 * 1. Phase 1 规划：LLM 同步调用获取 JSON 子任务列表
 * 2. Phase 2 执行：逐个子任务手动 ReAct 流式循环
 * 3. Phase 3 总结：LLM 流式调用生成最终总结
 * </p>
 * <p>
 * 设计模式：参考 {@link com.agentdemo.agent.single.ReActThinkingStream}，链式回调 + start() 同步执行。
 * Controller 注册回调后调用 start()，所有回调在 start() 内同步触发。
 * </p>
 * <p>
 * 关联 AC：AC-001~AC-016
 * </p>
 */
public class TaskBreakdownStream {

    private static final Logger log = LoggerFactory.getLogger(TaskBreakdownStream.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== 依赖 ====================
    private final String sessionId;
    private final String message;
    private final boolean enableThinking;
    private final ModelFactory modelFactory;
    private final ChatMemoryManager memoryManager;
    private final AgentConfig agentConfig;
    private final ToolSchemaConverter toolSchemaConverter;
    private final ToolExecutor toolExecutor;

    // ==================== 回调消费者 ====================
    // 规划阶段
    private PlanConsumer onPlan;
    private Runnable onNoBreakdown;

    // 子任务执行阶段
    private TaskStartConsumer onTaskStart;
    private TaskTokenConsumer onTaskToken;
    private TaskReasoningConsumer onTaskReasoning;
    private TaskThoughtConsumer onTaskThought;
    private TaskActionConsumer onTaskAction;
    private TaskObservationConsumer onTaskObservation;
    private TaskCompleteConsumer onTaskComplete;
    private TaskFailedConsumer onTaskFailed;
    private TaskCancelledConsumer onTaskCancelled;

    // 总结阶段
    private TokenConsumer onSummaryToken;
    private ReasoningConsumer onSummaryReasoning;

    // 生命周期
    private Runnable onComplete;
    private ErrorConsumer onError;

    // ==================== 构造器 ====================

    public TaskBreakdownStream(String sessionId, String message, boolean enableThinking,
                               ModelFactory modelFactory, ChatMemoryManager memoryManager,
                               AgentConfig agentConfig, ToolSchemaConverter toolSchemaConverter,
                               ToolExecutor toolExecutor) {
        this.sessionId = sessionId;
        this.message = message;
        this.enableThinking = enableThinking;
        this.modelFactory = modelFactory;
        this.memoryManager = memoryManager;
        this.agentConfig = agentConfig;
        this.toolSchemaConverter = toolSchemaConverter;
        this.toolExecutor = toolExecutor;
    }

    // ==================== 链式 Setter ====================

    public TaskBreakdownStream onPlan(PlanConsumer consumer) {
        this.onPlan = consumer;
        return this;
    }

    public TaskBreakdownStream onNoBreakdown(Runnable runnable) {
        this.onNoBreakdown = runnable;
        return this;
    }

    public TaskBreakdownStream onTaskStart(TaskStartConsumer consumer) {
        this.onTaskStart = consumer;
        return this;
    }

    public TaskBreakdownStream onTaskToken(TaskTokenConsumer consumer) {
        this.onTaskToken = consumer;
        return this;
    }

    public TaskBreakdownStream onTaskReasoning(TaskReasoningConsumer consumer) {
        this.onTaskReasoning = consumer;
        return this;
    }

    public TaskBreakdownStream onTaskThought(TaskThoughtConsumer consumer) {
        this.onTaskThought = consumer;
        return this;
    }

    public TaskBreakdownStream onTaskAction(TaskActionConsumer consumer) {
        this.onTaskAction = consumer;
        return this;
    }

    public TaskBreakdownStream onTaskObservation(TaskObservationConsumer consumer) {
        this.onTaskObservation = consumer;
        return this;
    }

    public TaskBreakdownStream onTaskComplete(TaskCompleteConsumer consumer) {
        this.onTaskComplete = consumer;
        return this;
    }

    public TaskBreakdownStream onTaskFailed(TaskFailedConsumer consumer) {
        this.onTaskFailed = consumer;
        return this;
    }

    public TaskBreakdownStream onTaskCancelled(TaskCancelledConsumer consumer) {
        this.onTaskCancelled = consumer;
        return this;
    }

    public TaskBreakdownStream onSummaryToken(TokenConsumer consumer) {
        this.onSummaryToken = consumer;
        return this;
    }

    public TaskBreakdownStream onSummaryReasoning(ReasoningConsumer consumer) {
        this.onSummaryReasoning = consumer;
        return this;
    }

    public TaskBreakdownStream onComplete(Runnable runnable) {
        this.onComplete = runnable;
        return this;
    }

    public TaskBreakdownStream onError(ErrorConsumer consumer) {
        this.onError = consumer;
        return this;
    }

    // ==================== start() 核心流程 ====================

    /**
     * 启动三阶段编排
     * <p>
     * 业务含义：同步执行规划 -> 执行 -> 总结三阶段，通过回调与 Controller 通信。
     * 异常时触发 onError，不抛出异常到调用方。
     * </p>
     */
    public void start() {
        try {
            // ===== Phase 1: 规划 =====
            List<SubTask> tasks = planTasks();

            if (tasks.isEmpty()) {
                // LLM 判断无需拆解，降级为普通对话（AC-002, AC-009）
                if (onNoBreakdown != null) {
                    onNoBreakdown.run();
                }
                // 降级路径：直接流式回答用户消息（Task-05 实现）
                streamDirectAnswer();
                if (onComplete != null) {
                    onComplete.run();
                }
                return;
            }

            // 规划成功，推送子任务列表（AC-001）
            if (onPlan != null) {
                onPlan.accept(tasks);
            }

            // ===== Phase 2: 逐个子任务执行（Task-04 实现）=====
            boolean allSuccess = executeAllSubTasks(tasks);

            if (!allSuccess) {
                // 子任务执行失败，不生成总结（AC-006）
                if (onComplete != null) {
                    onComplete.run();
                }
                return;
            }

            // ===== Phase 3: 总结（Task-05 实现）=====
            streamSummary(tasks);

            if (onComplete != null) {
                onComplete.run();
            }
        } catch (Exception e) {
            log.error("任务拆解编排异常: sessionId={}", sessionId, e);
            if (onError != null) {
                onError.accept(e);
            }
        }
    }

    // ==================== Phase 1: 规划 ====================

    /**
     * 规划阶段：调用 LLM 同步获取子任务列表
     * <p>
     * 业务含义：使用规划提示词 + 用户消息，调用 ChatModel.chat() 同步获取 LLM 响应，
     * 解析 JSON 数组为 SubTask 列表。解析失败或空列表时返回空列表（触发降级）。
     * </p>
     *
     * @return 子任务列表（空列表表示无需拆解）
     */
    private List<SubTask> planTasks() {
        ChatModel chatModel = modelFactory.getDefaultChatModel();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(agentConfig.getTaskBreakdownPlanPrompt()));
        messages.add(UserMessage.from(message));

        ChatResponse response = chatModel.chat(messages);
        String responseText = response.aiMessage().text();

        log.info("任务拆解规划响应: sessionId={}, responseLength={}", sessionId,
                responseText != null ? responseText.length() : 0);

        return parseTaskPlan(responseText);
    }

    /**
     * 解析 LLM 返回的 JSON 为子任务列表
     * <p>
     * 业务含义：尝试从 LLM 响应中提取 JSON 数组并解析为 SubTask 列表。
     * 支持 markdown 代码块包裹的 JSON。解析失败返回空列表（AC-009 降级）。
     * 子任务数量超过上限时截断（AC-008, AC-013）。
     * </p>
     *
     * @param responseText LLM 返回的文本
     * @return 解析后的子任务列表（空列表表示无需拆解或解析失败）
     */
    List<SubTask> parseTaskPlan(String responseText) {
        if (responseText == null || responseText.trim().isEmpty()) {
            return Collections.emptyList();
        }

        String json = extractJsonArray(responseText);
        if (json == null) {
            return Collections.emptyList();
        }

        try {
            JsonNode array = objectMapper.readTree(json);
            if (!array.isArray()) {
                return Collections.emptyList();
            }

            List<SubTask> tasks = new ArrayList<>();
            for (JsonNode node : array) {
                String title = node.path("title").asText("");
                if (title.isEmpty()) {
                    continue;
                }
                tasks.add(new SubTask(tasks.size() + 1, title));
            }

            // 子任务数量上限校验（AC-008, AC-013）
            int maxSubtasks = agentConfig.getTaskBreakdownMaxSubtasks();
            if (tasks.size() > maxSubtasks) {
                log.info("子任务数量 {} 超过上限 {}，截断", tasks.size(), maxSubtasks);
                tasks = new ArrayList<>(tasks.subList(0, maxSubtasks));
            }

            return tasks;
        } catch (Exception e) {
            log.warn("解析子任务 JSON 失败，降级为普通对话: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 从 LLM 响应文本中提取 JSON 数组
     * <p>
     * 业务含义：LLM 可能返回纯 JSON 或带 markdown 标记的 JSON。
     * 先尝试直接解析，失败后用正则提取 [...] 部分。
     * </p>
     *
     * @param text LLM 响应文本
     * @return JSON 数组字符串，无法提取时返回 null
     */
    private String extractJsonArray(String text) {
        String trimmed = text.trim();

        // 尝试直接解析
        try {
            JsonNode node = objectMapper.readTree(trimmed);
            if (node.isArray()) {
                return trimmed;
            }
        } catch (Exception ignored) {
            // 不是合法 JSON，继续尝试正则提取
        }

        // 正则提取 [...] 部分（处理 markdown 代码块包裹的情况）
        Pattern pattern = Pattern.compile("\\[.*?\\]", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(trimmed);
        if (matcher.find()) {
            return matcher.group();
        }

        return null;
    }

    // ==================== Phase 2: 子任务执行（Task-04 实现）====================

    /**
     * 执行所有子任务
     * <p>
     * 业务含义：遍历子任务列表，逐个执行 ReAct 循环。
     * 某个子任务失败时，取消剩余子任务并返回 false（AC-006）。
     * 每个子任务完成后将结果写入会话记忆，供后续子任务获取上下文。
     * </p>
     *
     * @param tasks 子任务列表
     * @return true=全部成功，false=有子任务失败
     */
    private boolean executeAllSubTasks(List<SubTask> tasks) {
        List<String> subtaskResults = new ArrayList<>();

        for (int i = 0; i < tasks.size(); i++) {
            SubTask task = tasks.get(i);

            // 推送子任务开始事件（AC-003: 状态 pending -> in-progress）
            if (onTaskStart != null) {
                onTaskStart.accept(task.index(), task.title());
            }

            try {
                String result = executeSubTaskWithReAct(task, subtaskResults);
                subtaskResults.add(result);

                // 将子任务结果写入会话记忆，供后续子任务和总结阶段获取上下文
                memoryManager.addUserMessage(sessionId, "子任务：" + task.title());
                memoryManager.addAssistantMessage(sessionId, result);

                // 推送子任务完成事件（AC-003: 状态 in-progress -> completed）
                if (onTaskComplete != null) {
                    onTaskComplete.accept(task.index());
                }
            } catch (Exception e) {
                log.error("子任务执行失败: index={}, title={}", task.index(), task.title(), e);
                // 推送子任务失败事件（AC-006）
                if (onTaskFailed != null) {
                    onTaskFailed.accept(task.index(), e.getMessage());
                }

                // 取消剩余子任务（AC-006: 失败即停，后续标记已取消）
                for (int j = i + 1; j < tasks.size(); j++) {
                    if (onTaskCancelled != null) {
                        onTaskCancelled.accept(tasks.get(j).index());
                    }
                }

                return false;
            }
        }

        return true;
    }

    /**
     * 执行单个子任务的 ReAct 循环
     * <p>
     * 业务含义：构造子任务执行消息（系统提示词 + 工具描述 + 历史记忆 + 子任务描述），
     * 调用 ArkThinkingStreamingChatModel.stream() 进行 ReAct 循环。
     * 每轮根据 finishReason 决定继续还是终止：
     * - stop: 子任务完成，返回累积的完整回答
     * - tool_calls: 执行工具，回填消息，继续循环
     * 达到 maxIterations 时返回已累积的部分内容。
     * </p>
     *
     * @param task            子任务
     * @param previousResults 之前子任务的执行结果列表
     * @return 子任务执行结果文本
     */
    private String executeSubTaskWithReAct(SubTask task, List<String> previousResults) {
        ThinkingStreamingChatModel thinkingModel = modelFactory.getThinkingStreamingChatModel();

        // 构造系统提示词：执行提示词 + 动态工具描述
        String systemPrompt = agentConfig.getTaskExecutionSystemPrompt()
                + "\n" + toolSchemaConverter.convertToDescriptionText();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        // 注入历史记忆（多轮上下文）
        messages.addAll(memoryManager.getMemory(sessionId).messages());

        // 构造子任务描述（包含之前子任务结果，提供上下文连贯性）
        StringBuilder userMessage = new StringBuilder();
        userMessage.append("子任务：").append(task.title());
        if (!previousResults.isEmpty()) {
            userMessage.append("\n之前子任务结果：\n");
            for (int i = 0; i < previousResults.size(); i++) {
                userMessage.append(i + 1).append(". ").append(previousResults.get(i)).append("\n");
            }
        }
        messages.add(UserMessage.from(userMessage.toString()));

        String toolsJson = toolSchemaConverter.convertToJson();
        int maxIterations = agentConfig.getTaskExecutionMaxIterations();

        int iteration = 0;
        StringBuilder fullResponse = new StringBuilder();

        while (iteration < maxIterations) {
            iteration++;
            final int currentIteration = iteration;

            IterationResult result = new IterationResult();
            ThinkingStreamHandler handler = createTaskHandler(task, result, currentIteration, fullResponse);

            // 同步调用 LLM（model.stream 内部会阻塞直到 SSE 流读取完毕）
            thinkingModel.stream(messages, toolsJson, handler);

            // 检查错误
            if (result.error != null) {
                throw new RuntimeException("子任务执行失败: " + result.error.getMessage(), result.error);
            }

            // 根据 finishReason 决定下一步
            if ("stop".equals(result.finishReason)) {
                // LLM 给出最终回答，子任务完成
                return fullResponse.toString();
            } else if ("tool_calls".equals(result.finishReason)) {
                // 推送本轮 ReAct 思考（AC-005: 子任务执行详情）
                if (!result.content.isEmpty() && onTaskThought != null) {
                    onTaskThought.accept(task.index(), result.content.toString(), currentIteration);
                }
                // 执行工具调用并回填消息
                executeToolCalls(task, result.toolCalls, currentIteration, messages);
            }
        }

        // 达到最大迭代次数，返回已累积的部分内容
        log.warn("子任务达到最大迭代次数: index={}, maxIterations={}", task.index(), maxIterations);
        return fullResponse.toString();
    }

    /**
     * 创建 ThinkingStreamHandler，将 LLM 回调桥接到 TaskBreakdownStream 的消费者
     * <p>
     * 回调映射：
     * - onPartialThinking -> onTaskReasoning（enableThinking=true 时，AC-011）
     * - onPartialResponse -> onTaskToken + 累积到 fullResponse（AC-005: 子任务内容片段）
     * - onToolCalls -> 收集工具调用
     * - onComplete -> 记录 finishReason
     * - onError -> 记录异常
     * </p>
     */
    private ThinkingStreamHandler createTaskHandler(SubTask task, IterationResult result,
                                                     int iteration, StringBuilder fullResponse) {
        return new ThinkingStreamHandler() {
            @Override
            public void onPartialThinking(String thinking) {
                // 方舟原生推理内容，仅 enableThinking=true 时推送（AC-011）
                if (enableThinking && onTaskReasoning != null) {
                    onTaskReasoning.accept(task.index(), thinking);
                }
            }

            @Override
            public void onPartialResponse(String token) {
                // 正式回复片段，流式推送 + 累积
                if (onTaskToken != null) {
                    onTaskToken.accept(task.index(), token);
                }
                fullResponse.append(token);
                result.content.append(token);
            }

            @Override
            public void onToolCalls(List<ToolCall> toolCalls) {
                result.toolCalls.addAll(toolCalls);
            }

            @Override
            public void onComplete(String response, String finishReason, TokenUsage tokenUsage) {
                result.finishReason = finishReason;
            }

            @Override
            public void onError(Throwable error) {
                result.error = error;
            }
        };
    }

    /**
     * 执行工具调用并回填消息（串行执行，参考 ReActThinkingStream）
     * <p>
     * 业务含义：遍历 toolCalls，逐个执行工具，推送 action/observation 事件，
     * 并回填 assistant 消息（含 tool_calls）和 tool 结果消息到消息列表。
     * </p>
     *
     * @param task       子任务
     * @param toolCalls  工具调用列表
     * @param iteration  当前迭代轮次
     * @param messages   消息列表（回填工具结果）
     */
    private void executeToolCalls(SubTask task, List<ToolCall> toolCalls, int iteration,
                                   List<ChatMessage> messages) {
        // 回填 assistant 消息（含 toolExecutionRequests），供下一轮 LLM 理解上下文
        List<ToolExecutionRequest> requests = toolCalls.stream()
                .map(tc -> ToolExecutionRequest.builder()
                        .id(tc.getId())
                        .name(tc.getFunctionName())
                        .arguments(tc.getArguments())
                        .build())
                .toList();
        messages.add(AiMessage.aiMessage("", requests));

        // 串行执行每个工具调用
        for (ToolCall tc : toolCalls) {
            // 推送 action 事件（AC-005: 工具调用详情）
            if (onTaskAction != null) {
                onTaskAction.accept(task.index(), tc.getFunctionName(), tc.getArguments(), iteration);
            }

            // 执行工具，失败时返回错误字符串不抛异常（与 ToolExecutor 设计一致）
            String toolResult = toolExecutor.execute(tc.getFunctionName(), tc.getArguments());

            // 推送 observation 事件（AC-005: 工具结果）
            if (onTaskObservation != null) {
                onTaskObservation.accept(task.index(), toolResult, iteration);
            }

            // 回填 tool 结果消息，供下一轮 LLM 获取工具执行结果
            messages.add(ToolExecutionResultMessage.from(tc.getId(), tc.getFunctionName(), toolResult));
        }
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

    // ==================== Phase 3: 总结（Task-05 实现）====================

    /**
     * 总结阶段：流式输出最终总结
     * <p>
     * 业务含义：所有子任务完成后，调用 LLM 流式生成最终总结（AC-004）。
     * 总结消息包含总结提示词 + 会话记忆（含子任务执行结果）。
     * 不传 tools 参数，总结阶段不需要工具调用。
     * </p>
     */
    private void streamSummary(List<SubTask> tasks) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(agentConfig.getTaskSummaryPrompt()));
        // 历史记忆中已包含用户消息和各子任务执行结果
        messages.addAll(memoryManager.getMemory(sessionId).messages());

        streamResponse(messages);
    }

    /**
     * 降级路径：直接流式回答用户消息
     * <p>
     * 业务含义：LLM 判断无需拆解时，直接以普通对话方式流式回复（AC-002, AC-009）。
     * 使用思考系统提示词（不提及工具调用，避免模型尝试调用不存在的工具）。
     * </p>
     */
    private void streamDirectAnswer() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(agentConfig.getThinkingSystemPrompt()));
        messages.addAll(memoryManager.getMemory(sessionId).messages());
        messages.add(UserMessage.from(message));

        streamResponse(messages);
    }

    /**
     * 流式输出通用方法（总结阶段和降级路径共用）
     * <p>
     * 业务含义：调用 ArkThinkingStreamingChatModel.stream() 流式输出，
     * 通过 onSummaryToken 回调推送文本片段，通过 onSummaryReasoning 回调推送推理片段。
     * 不传 tools 参数（总结/降级阶段不需要工具调用）。
     * LLM 调用失败时抛出 RuntimeException，由 start() 的 catch 块捕获并触发 onError。
     * </p>
     *
     * @param messages 消息列表
     */
    private void streamResponse(List<ChatMessage> messages) {
        ThinkingStreamingChatModel thinkingModel = modelFactory.getThinkingStreamingChatModel();
        final Throwable[] errorHolder = {null};

        ThinkingStreamHandler handler = new ThinkingStreamHandler() {
            @Override
            public void onPartialThinking(String thinking) {
                // 方舟原生推理内容，仅 enableThinking=true 时推送
                if (enableThinking && onSummaryReasoning != null) {
                    onSummaryReasoning.accept(thinking);
                }
            }

            @Override
            public void onPartialResponse(String token) {
                // 正式回复片段，流式推送
                if (onSummaryToken != null) {
                    onSummaryToken.accept(token);
                }
            }

            @Override
            public void onToolCalls(List<ToolCall> toolCalls) {
                // 总结/降级阶段不需要工具调用
            }

            @Override
            public void onComplete(String fullResponse, String finishReason, TokenUsage tokenUsage) {
                log.info("流式输出完成: sessionId={}, finishReason={}", sessionId, finishReason);
            }

            @Override
            public void onError(Throwable error) {
                log.error("流式输出异常: sessionId={}", sessionId, error);
                errorHolder[0] = error;
            }
        };

        // 不带 tools 参数调用 LLM
        thinkingModel.stream(messages, null, handler);

        // 如果 LLM 调用失败，抛出异常由 start() 的 catch 块处理
        if (errorHolder[0] != null) {
            throw new RuntimeException("流式输出失败: " + errorHolder[0].getMessage(), errorHolder[0]);
        }
    }

    // ==================== 回调接口定义 ====================

    /** 规划完成回调（携带子任务列表） */
    @FunctionalInterface
    public interface PlanConsumer {
        void accept(List<SubTask> tasks);
    }

    /** 子任务开始回调 */
    @FunctionalInterface
    public interface TaskStartConsumer {
        void accept(int index, String title);
    }

    /** 子任务内容片段回调 */
    @FunctionalInterface
    public interface TaskTokenConsumer {
        void accept(int index, String content);
    }

    /** 子任务推理片段回调 */
    @FunctionalInterface
    public interface TaskReasoningConsumer {
        void accept(int index, String content);
    }

    /** 子任务 ReAct 思考回调 */
    @FunctionalInterface
    public interface TaskThoughtConsumer {
        void accept(int index, String content, int iteration);
    }

    /** 子任务工具调用回调 */
    @FunctionalInterface
    public interface TaskActionConsumer {
        void accept(int index, String toolName, String args, int iteration);
    }

    /** 子任务工具结果回调 */
    @FunctionalInterface
    public interface TaskObservationConsumer {
        void accept(int index, String result, int iteration);
    }

    /** 子任务完成回调 */
    @FunctionalInterface
    public interface TaskCompleteConsumer {
        void accept(int index);
    }

    /** 子任务失败回调 */
    @FunctionalInterface
    public interface TaskFailedConsumer {
        void accept(int index, String error);
    }

    /** 子任务取消回调 */
    @FunctionalInterface
    public interface TaskCancelledConsumer {
        void accept(int index);
    }

    /** 总结文本片段回调 */
    @FunctionalInterface
    public interface TokenConsumer {
        void accept(String token);
    }

    /** 总结推理片段回调 */
    @FunctionalInterface
    public interface ReasoningConsumer {
        void accept(String reasoning);
    }

    /** 异常回调 */
    @FunctionalInterface
    public interface ErrorConsumer {
        void accept(Throwable error);
    }
}
