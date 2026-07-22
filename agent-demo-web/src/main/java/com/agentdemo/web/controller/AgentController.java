package com.agentdemo.web.controller;

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

import java.io.IOException;
import java.util.UUID;

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
    private final SessionManager sessionManager;
    private final ChatMemoryManager memoryManager;

    public AgentController(SimpleAgent simpleAgent, SessionManager sessionManager, ChatMemoryManager memoryManager) {
        this.simpleAgent = simpleAgent;
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

        // 累积完整回复，流式完成后写入记忆（供后续多轮上下文使用）
        StringBuilder fullResponse = new StringBuilder();
        long start = System.currentTimeMillis();

        // 业务含义：根据 enableThinking 分流（CR-001 新增）
        // - true：走思考流式路径，推送 reasoning + token 事件
        // - false/null：走原 agent.chatStream 路径，仅推送 token 事件（零回归）
        if (Boolean.TRUE.equals(request.getEnableThinking())) {
            // 思考流式路径（CR-001 新增）
            simpleAgent.chatThinkingStream(effectiveSessionId, request.getMessage())
                    .onPartialThinking(thinking -> sendEvent(emitter, "reasoning", thinking))
                    .onPartialResponse(token -> {
                        sendEvent(emitter, "token", token);
                        fullResponse.append(token);
                    })
                    .onComplete(fullResponseStr -> {
                        // 业务含义：流式完成后，将完整助手回复写入记忆（不含推理内容），保证下一轮对话有上下文
                        memoryManager.addAssistantMessage(effectiveSessionId, fullResponse.toString());
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
            simpleAgent.chatStream(effectiveSessionId, request.getMessage())
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
     * 发送 SSE 事件（统一异常处理，避免 IOException 中断主流程）
     *
     * @param emitter  SSE 发射器
     * @param eventName 事件名
     * @param data      事件数据
     */
    private void sendEvent(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException e) {
            // 客户端可能已断开连接，降级为 WARN 日志，不抛异常
            log.warn("SSE 发送失败（客户端可能已断开）: event={}", eventName);
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
