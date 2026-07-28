package com.agentdemo.web.controller;

import com.agentdemo.agent.core.SubTask;
import com.agentdemo.agent.core.TaskBreakdownStream;
import com.agentdemo.agent.single.PlanAgent;
import com.agentdemo.agent.single.SimpleAgent;
import com.agentdemo.common.result.Result;
import com.agentdemo.memory.shortterm.ChatMemoryManager;
import com.agentdemo.memory.session.SessionManager;
import com.agentdemo.web.dto.ChatRequest;
import com.agentdemo.web.dto.ChatResponse;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.TokenStream;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Agent 对话接口
 * <p>
 * 业务含义：提供 Agent 对话的 REST API，支持同步对话、流式对话、会话管理。
 * 接口路径规范：统一 /api/agent/* 前缀
 * </p>
 */
@Tag(name = "Agent 对话", description = "Agent 对话与会话管理接口")
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    /**
     * Agent 实例（CR-001 调整：由 BaseAgent 接口改为 SimpleAgent 具体类型）
     * 业务含义：SimpleAgent 实现 BaseAgent 接口，同时提供 chat/chatStream（原路径）和
     * chatThinkingStream（CR-001 思考路径）。注入具体类型避免 BaseAgent 类型多候选注入歧义。
     */
    private final SimpleAgent simpleAgent;
    private final PlanAgent planAgent;
    private final SessionManager sessionManager;
    private final ChatMemoryManager memoryManager;

    public AgentController(SimpleAgent simpleAgent, PlanAgent planAgent, SessionManager sessionManager, ChatMemoryManager memoryManager) {
        this.simpleAgent = simpleAgent;
        this.planAgent = planAgent;
        this.sessionManager = sessionManager;
        this.memoryManager = memoryManager;
    }

    /**
     * 同步对话
     * 业务含义：接收用户消息，调用 Agent 获取回复，返回会话 ID 供多轮对话使用
     *
     * @param request 对话请求
     * @return 对话响应
     */
    @Operation(summary = "同步对话", description = "发送消息给 Agent，获取同步回复")
    @PostMapping("/chat")
    public Result<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        // 会话管理：sessionId 为空则新建
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = sessionManager.createSession();
        } else if (!sessionManager.exists(sessionId)) {
            // 业务含义：传入的 sessionId 不存在时新建，避免前端传入无效 ID 导致错误
            sessionId = sessionManager.createSession();
        }

        // 记录用户消息到记忆
        memoryManager.addUserMessage(sessionId, request.getMessage());

        // 调用 Agent（ReAct 循环由 LangChain4j 自动处理）
        long start = System.currentTimeMillis();
        String response = simpleAgent.chat(sessionId, request.getMessage());
        long duration = System.currentTimeMillis() - start;

        // 记录助手回复到记忆
        memoryManager.addAssistantMessage(sessionId, response);

        ChatResponse chatResponse = new ChatResponse(
                sessionId, response, null, duration, null);
        return Result.success(chatResponse);
    }

    /**
     * 流式对话
     * <p>
     * 业务含义：接收用户消息，流式返回大模型生成内容（SSE 逐字推送）。
     * 透明续聊：sessionId 无效时自动新建会话，通过 session 事件通知前端更新关联。
     * </p>
     * <p>
     * SSE 事件协议：
     * - session: 携带 sessionId（首次或会话超时新建时发送）
     * - token: 携带文本片段（逐字输出）
     * - done: 流式完成（携带耗时毫秒）
     * - error: 错误信息
     * </p>
     *
     * @param request 对话请求
     * @return SseEmitter 流式响应
     */
    @Operation(summary = "流式对话", description = "发送消息给 Agent，流式返回生成内容（SSE）")
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody ChatRequest request) {
        // SSE 超时与 Tomcat connection-timeout 对齐（5 分钟）
        SseEmitter emitter = new SseEmitter(300_000L);

        // 业务含义：空消息校验（AC-015），避免无效请求消耗会话与 LLM 资源
        if (request.getMessage() == null || request.getMessage().trim().isEmpty()) {
            sendEvent(emitter, "error", "消息不能为空");
            emitter.complete();
            return emitter;
        }

        // 会话管理：sessionId 为空或不存在则新建（BR-MEM-005、BR-WEB-008）
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isEmpty() || !sessionManager.exists(sessionId)) {
            sessionId = sessionManager.createSession();
            // 通知前端会话 ID（支持透明续聊：前端据此更新本地会话关联）
            sendEvent(emitter, "session", sessionId);
        }

        // sessionId 在 if 中可能被重新赋值，lambda 要求 effectively final，故用 final 变量
        final String effectiveSessionId = sessionId;

        // 记录用户消息到记忆
        memoryManager.addUserMessage(effectiveSessionId, request.getMessage());

        // 业务含义：用户指定知识库时，将知识库名称注入用户消息末尾，
        // 引导 LLM 在 ReAct 循环中调用 searchKnowledge 工具时使用指定知识库。
        // 不指定时走原有路径（Agent 自主决策），零回归。
        final String effectiveMessage;
        List<String> knowledgeBases = request.getKnowledgeBases();
        if (knowledgeBases != null && !knowledgeBases.isEmpty()) {
            effectiveMessage = request.getMessage()
                + "\n\n[系统提示：请优先使用以下知识库检索相关信息："
                + String.join("、", knowledgeBases) + "]";
        } else {
            effectiveMessage = request.getMessage();
        }

        // 累积完整回复，流式完成后写入记忆（供后续多轮上下文使用）
        StringBuilder fullResponse = new StringBuilder();
        long start = System.currentTimeMillis();

        // 业务含义：任务拆解分流（CR-002 新增）
        // - enableTaskBreakdown=true：走 PlanAgent.chatTaskBreakdownStream 路径
        // - enableTaskBreakdown=false/null：继续检查 enableThinking 分支
        if (Boolean.TRUE.equals(request.getEnableTaskBreakdown())) {
            // 业务含义：异步执行任务拆解编排，确保 SSE 事件实时推送到客户端。
            // 若在请求线程同步执行 start()，Spring SseEmitter 在 Controller 返回前无法初始化 handler，
            // 所有 send() 数据被缓存到 earlySendAttempts，直到全部完成后才一次性发送，
            // 导致前端无法实时看到任务拆解和执行进度。
            CompletableFuture.runAsync(() ->
                planAgent.chatTaskBreakdownStream(effectiveSessionId, effectiveMessage,
                    Boolean.TRUE.equals(request.getEnableThinking()))
                .onPlan(tasks -> {
                    // 推送子任务列表（AC-001）
                    List<Map<String, Object>> taskList = new ArrayList<>();
                    for (SubTask task : tasks) {
                        Map<String, Object> map = new LinkedHashMap<>();
                        map.put("index", task.index());
                        map.put("title", task.title());
                        taskList.add(map);
                    }
                    sendEvent(emitter, "task_plan", Map.of("tasks", taskList));
                })
                .onNoBreakdown(() -> { /* 无事件，后续 token 事件自动处理 */ })
                .onTaskStart((index, title) -> sendEvent(emitter, "task_start",
                        Map.of("index", index, "title", title)))
                .onTaskToken((index, content) -> sendEvent(emitter, "task_token",
                        Map.of("index", index, "content", content)))
                .onTaskReasoning((index, content) -> sendEvent(emitter, "task_reasoning",
                        Map.of("index", index, "content", content)))
                .onTaskThought((index, content, iter) -> sendEvent(emitter, "task_thought",
                        Map.of("index", index, "content", content, "iteration", iter)))
                .onTaskAction((index, name, args, iter) -> sendEvent(emitter, "task_action",
                        Map.of("index", index, "toolName", name, "args", args, "iteration", iter)))
                .onTaskObservation((index, result, iter) -> sendEvent(emitter, "task_observation",
                        Map.of("index", index, "result", result, "iteration", iter)))
                .onTaskComplete(index -> sendEvent(emitter, "task_complete",
                        Map.of("index", index)))
                .onTaskFailed((index, error) -> sendEvent(emitter, "task_failed",
                        Map.of("index", index, "error", error)))
                .onTaskCancelled(index -> sendEvent(emitter, "task_cancelled",
                        Map.of("index", index)))
                .onSummaryToken(token -> {
                    // 业务含义：总结阶段文本片段，推送给前端 + 累积完整回复（供 onComplete 写入记忆）
                    sendEvent(emitter, "token", token);
                    fullResponse.append(token);
                })
                .onSummaryReasoning(reasoning -> sendEvent(emitter, "reasoning", reasoning))
                .onComplete(() -> {
                    // 业务含义：将总结回复写入记忆，保证下一轮对话有上下文（与其他路径对齐）
                    if (fullResponse.length() > 0) {
                        memoryManager.addAssistantMessage(effectiveSessionId, fullResponse.toString());
                    }
                    sendEvent(emitter, "done", System.currentTimeMillis() - start);
                    emitter.complete();
                })
                .onError(error -> {
                    log.error("任务拆解异常: sessionId={}", effectiveSessionId, error);
                    sendEvent(emitter, "error", "任务拆解执行失败，请重试");
                    emitter.complete();
                })
                .start()
            );
            return emitter;
        }

        // 业务含义：根据 enableThinking 分流（CR-001 新增，Task-09 扩展为 ReAct）
        // - true：走 ReAct 思考流式路径，推送 reasoning + thought + action + observation + final-answer 事件
        // - false/null：走原 agent.chatStream 路径，仅推送 token 事件（零回归）
        if (Boolean.TRUE.equals(request.getEnableThinking())) {
            // ReAct 思考流式路径（Task-09 新增）
            // 业务含义：ReAct 模式中 content 通过 onPartialThought 推送为 thought 事件，
            // 不再使用 onPartialResponse（ReActThinkingStream 中为空实现）
            simpleAgent.chatThinkingReActStream(effectiveSessionId, effectiveMessage)
                    .onPartialThinking(thinking -> sendEvent(emitter, "reasoning", thinking))
                    .onPartialThought((thought, iteration) -> sendEvent(emitter, "thought", Map.of("content", thought, "iteration", iteration)))
                    .onAction((toolName, arguments, iteration) -> sendEvent(emitter, "action", Map.of("toolName", toolName, "arguments", arguments, "iteration", iteration)))
                    .onObservation((result, iteration) -> sendEvent(emitter, "observation", Map.of("result", result, "iteration", iteration)))
                    .onFinalAnswer(iteration -> sendEvent(emitter, "final-answer", Map.of("iteration", iteration)))
                    .onComplete(fullResponseStr -> {
                        // 业务含义：流式完成后，将完整最终回答写入记忆（不含推理/工具过程），保证下一轮对话有上下文
                        // ReActThinkingStream 的 onComplete 携带完整最终回答文本，直接使用参数而非 StringBuilder
                        memoryManager.addAssistantMessage(effectiveSessionId, fullResponseStr);
                        long duration = System.currentTimeMillis() - start;
                        sendEvent(emitter, "done", duration);
                        emitter.complete();
                    })
                    .onError(error -> {
                        // 业务含义：思考流式过程中的异常通过 SSE error 事件通知前端
                        log.error("思考流式对话异常: sessionId={}", effectiveSessionId, error);
                        sendEvent(emitter, "error", "生成回复时发生错误，请重试");
                        emitter.complete();
                    })
                    .start();
        } else {
            // 原路径（零回归）
            simpleAgent.chatStream(effectiveSessionId, effectiveMessage)
                    .onPartialResponse(token -> {
                        sendEvent(emitter, "token", token);
                        fullResponse.append(token);
                    })
                    .onCompleteResponse(response -> {
                        // 业务含义：流式完成后，将完整助手回复写入记忆，保证下一轮对话有上下文
                        memoryManager.addAssistantMessage(effectiveSessionId, fullResponse.toString());
                        long duration = System.currentTimeMillis() - start;
                        sendEvent(emitter, "done", duration);
                        emitter.complete();
                    })
                    .onError(error -> {
                        // 业务含义：流式过程中的异常无法走 GlobalExceptionHandler（响应已开始），
                        // 通过 SSE error 事件通知前端
                        log.error("流式对话异常: sessionId={}", effectiveSessionId, error);
                        sendEvent(emitter, "error", "生成回复时发生错误，请重试");
                        emitter.complete();
                    })
                    .start();
        }

        return emitter;
    }

    /**
     * 发送 SSE 事件（统一异常处理，避免异常中断后续 SSE 事件推送）
     *
     * @param emitter  SSE 发射器
     * @param eventName 事件名
     * @param data      事件数据
     */
    private void sendEvent(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (Exception e) {
            // 业务含义：客户端断开（IOException）或 emitter 已超时/完成（IllegalStateException），
            // 降级为 WARN 日志，不抛异常，避免中断后续 SSE 事件推送
            log.warn("SSE 发送失败: event={}, reason={}", eventName, e.getMessage());
        }
    }

    /**
     * 创建新会话
     *
     * @return 会话 ID
     */
    @Operation(summary = "创建会话", description = "创建新的对话会话")
    @PostMapping("/session")
    public Result<String> createSession() {
        String sessionId = sessionManager.createSession();
        return Result.success(sessionId);
    }

    /**
     * 查询会话是否存在
     *
     * @param sessionId 会话 ID
     * @return 是否存在
     */
    @Operation(summary = "查询会话", description = "查询指定会话是否存在")
    @GetMapping("/session/{sessionId}")
    public Result<Boolean> existsSession(@PathVariable String sessionId) {
        return Result.success(sessionManager.exists(sessionId));
    }

    /**
     * 清空会话记忆
     * 业务含义：删除指定会话的对话历史，重新开始对话
     *
     * @param sessionId 会话 ID
     * @return 操作结果
     */
    @Operation(summary = "清空记忆", description = "清空指定会话的对话记忆")
    @DeleteMapping("/session/{sessionId}/memory")
    public Result<Void> clearMemory(@PathVariable String sessionId) {
        memoryManager.clearMemory(sessionId);
        return Result.success();
    }
}
