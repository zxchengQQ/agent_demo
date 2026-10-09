package com.agentdemo.web.controller;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.agent.core.PendingInteraction;
import com.agentdemo.agent.core.HumanInteractionManager;
import com.agentdemo.agent.core.TaskPlanJudge;
import com.agentdemo.agent.core.SubTask;
import com.agentdemo.agent.prompt.PromptTemplateLoader;
import com.agentdemo.agent.single.PlanAgent;
import com.agentdemo.agent.single.SessionToolResolver;
import com.agentdemo.agent.single.SimpleAgent;
import com.agentdemo.llm.registry.ModelFactory;
import com.agentdemo.llm.thinking.ThinkingStreamHandler;
import com.agentdemo.llm.thinking.ThinkingStreamingChatModel;
import com.agentdemo.memory.shortterm.ChatMemoryManager;
import com.agentdemo.memory.shortterm.MemoryCompressionProperties;
import com.agentdemo.memory.session.SessionManager;
import com.agentdemo.observability.NoopTraceCollector;
import com.agentdemo.skill.session.SkillSessionManager;
import com.agentdemo.tools.builtin.AskUserTool;
import com.agentdemo.tools.permission.ToolPermissionLevel;
import com.agentdemo.tools.registry.ToolExecutor;
import com.agentdemo.tools.registry.ToolRegistry;
import com.agentdemo.tools.registry.ToolSchemaConverter;
import com.agentdemo.web.dto.ChatRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * BUG 复现测试：多轮人机交互（HITL）后流程卡死
 * <p>
 * 复现场景（用户报告）：发送"帮我查询一下今天的新闻"后，经过多轮人机交互
 * （askUser 追问 -> 用户回答 -> ask 级工具 tool_confirm -> 用户批准 -> 再次 askUser -> 用户回答），
 * 流程卡死在"生成中"，后端无后续日志，前端页面卡死。
 * </p>
 * <p>
 * 本测试用桩模型脚本化 4 轮交互，驱动真实链路：
 * AgentController.chatStream -> PlanAgent/UnifiedChatStream -> HITLReActStream -> HumanInteractionManager，
 * 逐轮断言 SSE 事件、emitter 生命周期与 pending 状态机，定位卡死环节。
 * </p>
 */
class AgentControllerHitlMultiRoundBugTest {

    private ModelFactory modelFactory;
    private ToolRegistry toolRegistry;
    private ToolExecutor toolExecutor;
    private HumanInteractionManager him;
    private ChatMemoryManager memoryManager;
    private SessionManager sessionManager;
    private AgentController controller;
    private ThinkingStreamingChatModel model;

    /** 脚本化模型：每轮 LLM 调用按入队脚本回放 handler 回调 */
    private Queue<java.util.function.Consumer<ThinkingStreamHandler>> script = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void setUp() {
        modelFactory = mock(ModelFactory.class);
        toolRegistry = mock(ToolRegistry.class);
        toolExecutor = mock(ToolExecutor.class);
        him = new HumanInteractionManager();
        AgentConfig agentConfig = new AgentConfig();
        memoryManager = new ChatMemoryManager(modelFactory, new MemoryCompressionProperties());
        sessionManager = new SessionManager();

        model = mock(ThinkingStreamingChatModel.class);
        doAnswer(inv -> {
            java.util.function.Consumer<ThinkingStreamHandler> round = script.poll();
            if (round != null) {
                round.accept(inv.getArgument(2));
            }
            return null;
        }).when(model).stream(anyList(), any(), any(ThinkingStreamHandler.class));
        lenient().when(modelFactory.getDefaultThinkingStreamingChatModel()).thenReturn(model);
        lenient().when(modelFactory.getThinkingStreamingChatModelByModelId(anyString())).thenReturn(model);

        // 默认工具集为空；askUser 工具按需加载返回真实实例
        lenient().when(toolRegistry.getDefaultToolsForStreaming(any())).thenReturn(List.of());
        lenient().when(toolRegistry.resolveToolsForStreaming(any())).thenReturn(List.of(new AskUserTool()));

        // ask 级工具（模拟 builtin:http）——拦截确认流；其余工具 ALLOW
        // 注意 stub 顺序：后注册的 stub 优先，故 anyString 先注册、eq("builtin:http") 后注册
        lenient().when(toolExecutor.checkPermission(anyString()))
                .thenReturn(new ToolExecutor.ToolPermissionCheck(
                        ToolPermissionLevel.ALLOW, "builtin:http", "HTTP GET 工具"));
        lenient().when(toolExecutor.checkPermission(eq("builtin:http")))
                .thenReturn(new ToolExecutor.ToolPermissionCheck(
                        ToolPermissionLevel.ASK, "builtin:http", "HTTP GET 工具"));
        lenient().when(toolExecutor.execute(eq("builtin:http"), anyString()))
                .thenReturn("【新闻快讯】2026-09-02 今日要闻……");

        TaskPlanJudge taskPlanJudge = mock(TaskPlanJudge.class);
        lenient().when(taskPlanJudge.judge(any(), any(), any(), any())).thenReturn(List.of());

        PlanAgent planAgent = new PlanAgent(modelFactory, memoryManager, agentConfig,
                new ToolSchemaConverter(toolRegistry), toolExecutor,
                new PromptTemplateLoader(agentConfig), him,
                new SessionToolResolver(toolRegistry, agentConfig), taskPlanJudge);

        controller = new AgentController(mock(SimpleAgent.class), planAgent, sessionManager, memoryManager,
                toolRegistry, agentConfig, him, mock(com.agentdemo.tools.permission.ToolPermissionService.class),
                mock(SkillSessionManager.class), new NoopTraceCollector());
    }

    // ==================== SSE 捕获工具 ====================

    /** 捕获单个 emitter 的 SSE 事件与生命周期（容器外注入 Handler 代理 + 早期缓存兜底） */
    private static class SseCapture {
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        volatile boolean completedFlag = false;
        private SseEmitter emitter;

        boolean completed() {
            // 反射读取 emitter 内部完成标志（handler 注入前的 complete() 也置位该标志）
            try {
                Field f = ResponseBodyEmitter.class.getDeclaredField("complete");
                f.setAccessible(true);
                return completedFlag || f.getBoolean(emitter);
            } catch (Exception e) {
                return completedFlag;
            }
        }

        static SseCapture attach(SseEmitter emitter) {
            SseCapture capture = new SseCapture();
            capture.emitter = emitter;
            // 兜底：读取 handler 注入前进 earlySendAttempts 缓存的事件（异步任务先于注入完成时）
            try {
                Field early = ResponseBodyEmitter.class.getDeclaredField("earlySendAttempts");
                early.setAccessible(true);
                Object set = early.get(emitter);
                if (set instanceof java.util.Set<?> s) {
                    for (Object item : s) {
                        try {
                            java.lang.reflect.Method getData = item.getClass().getMethod("getData");
                            getData.setAccessible(true);
                            Object data = getData.invoke(item);
                            if (data != null) {
                                capture.events.add(data instanceof java.util.Map<?, ?> m
                                        ? new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(m)
                                        : data.toString());
                            }
                        } catch (Exception ignored) {
                            // 单片段提取失败容错
                        }
                    }
                }
            } catch (Exception ignored) {
                // 早期缓存读取失败容错
            }
            try {
                Class<?> handlerClass = Class.forName(
                        "org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter$Handler");
                Object handler = Proxy.newProxyInstance(handlerClass.getClassLoader(),
                        new Class<?>[]{handlerClass},
                        (proxy, method, args) -> {
                            switch (method.getName()) {
                                case "send" -> {
                                    if (args != null && args[0] != null) {
                                        if (args[0] instanceof java.util.Set<?> set) {
                                            for (Object item : set) {
                                                try {
                                                    java.lang.reflect.Method getData = item.getClass().getMethod("getData");
                                                    getData.setAccessible(true);
                                                    Object data = getData.invoke(item);
                                                    if (data != null) {
                                                        capture.events.add(data instanceof java.util.Map<?, ?> m
                                                                ? new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(m)
                                                                : data.toString());
                                                    }
                                                } catch (Exception ignored) {
                                                    // 单片段提取失败容错
                                                }
                                            }
                                        } else {
                                            capture.events.add(args[0].toString());
                                        }
                                    }
                                }
                                case "complete" -> capture.completedFlag = true;
                                case "completeWithError" -> capture.completedFlag = true;
                                default -> {
                                    // onTimeout/onError 注册等其余方法不处理
                                }
                            }
                            return null;
                        });
                Field handlerField = ResponseBodyEmitter.class.getDeclaredField("handler");
                handlerField.setAccessible(true);
                handlerField.set(emitter, handler);
            } catch (Exception e) {
                throw new IllegalStateException("SSE handler 注入失败", e);
            }
            return capture;
        }

        String all() {
            synchronized (events) {
                return String.join("", events);
            }
        }

        boolean has(String fragment) {
            return all().contains(fragment);
        }
    }

    private ChatRequest request(String sessionId, String message, Boolean toolApproved) {
        ChatRequest request = new ChatRequest();
        request.setSessionId(sessionId);
        request.setMessage(message);
        request.setToolApproved(toolApproved);
        return request;
    }

    private void await(Supplier<Boolean> condition, String what) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(condition.get())) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // 诊断：超时时转储 ForkJoinPool 工作线程栈 + 早期事件缓存 + model 调用统计
        Thread.getAllStackTraces().forEach((t, st) -> {
            if (t.getName().contains("commonPool") || t.getName().contains("ForkJoinPool")) {
                System.err.println("==== 线程栈: " + t.getName() + " state=" + t.getState());
                for (StackTraceElement e : st) {
                    System.err.println("    at " + e);
                }
            }
        });
        System.err.println("==== model.stream 调用次数: "
                + mockingDetails(model).getInvocations().stream()
                        .filter(inv -> "stream".equals(inv.getMethod().getName())).count());
        fail("等待超时: " + what);
    }

    private void scriptAskUser(String callId, String question) {
        script.add(handler -> {
            dev.langchain4j.agent.tool.ToolExecutionRequest req = dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                    .id(callId).name("askUser")
                    .arguments("{\"type\":\"text\",\"question\":\"" + question + "\",\"options\":[]}")
                    .build();
            handler.onToolCalls(List.of(toToolCall(req)));
            handler.onComplete("需要向用户提问", "tool_calls", null);
        });
    }

    private void scriptToolCall(String callId, String toolName, String args) {
        script.add(handler -> {
            handler.onToolCalls(List.of(newToolCall(callId, toolName, args)));
            handler.onComplete("需要调用工具", "tool_calls", null);
        });
    }

    private void scriptStop(String answer) {
        script.add(handler -> {
            handler.onPartialResponse(answer);
            handler.onComplete(answer, "stop", null);
        });
    }

    private com.agentdemo.llm.thinking.ToolCall toToolCall(dev.langchain4j.agent.tool.ToolExecutionRequest req) {
        return newToolCall(req.id(), req.name(), req.arguments());
    }

    private com.agentdemo.llm.thinking.ToolCall newToolCall(String id, String name, String args) {
        com.agentdemo.llm.thinking.ToolCall tc = new com.agentdemo.llm.thinking.ToolCall();
        tc.setId(id);
        tc.setFunctionName(name);
        tc.setArguments(args);
        return tc;
    }

    // ==================== 复现：多轮人机交互全链路 ====================

    @Test
    @DisplayName("复现：askUser -> 回答 -> tool_confirm -> 批准 -> askUser -> 回答 -> 最终回答，全链路不卡死")
    void 多轮人机交互全链路() {
        // ---------- 轮 1：用户发送"帮我查询一下今天的新闻"，Agent askUser 追问 ----------
        String sessionId = sessionManager.createSession();
        scriptAskUser("call_1", "请问您想查询哪类新闻？");
        SseCapture cap1 = SseCapture.attach(controller.chatStream(request(sessionId, "帮我查询一下今天的新闻", null)));

        await(cap1::completed, "轮 1 ask_user 后 done + complete");
        assertTrue(cap1.has("ask_user"), "轮 1 应有 ask_user 事件: " + cap1.all());
        assertTrue(cap1.has("done"), "轮 1 应有 done 事件: " + cap1.all());
        assertTrue(him.hasPending(sessionId), "轮 1 暂停后应保存 pending");
        PendingInteraction p1 = him.loadInteraction(sessionId);
        assertEquals(PendingInteraction.MODE_DIRECT, p1.getMode(), "轮 1 暂停模式应为 direct");
        assertEquals(0, p1.getRetryCount(), "轮 1 追问计数应为 0");

        // ---------- 轮 2：用户回答"科技新闻"，Agent 请求 ask 级工具（tool_confirm，流终止） ----------
        scriptToolCall("call_2", "builtin:http", "{\"url\":\"https://news.example.com/today\"}");
        SseCapture cap2 = SseCapture.attach(controller.chatStream(request(sessionId, "科技新闻", null)));

        await(() -> him.loadInteraction(sessionId) != null
                        && PendingInteraction.MODE_TOOL_CONFIRM.equals(him.loadInteraction(sessionId).getMode()),
                "轮 2 tool_confirm 暂停保存 [诊断] cap2完成=" + cap2.completed()
                        + " cap2事件=" + cap2.all());
        assertTrue(cap2.has("tool_confirm"), "轮 2 应有 tool_confirm 事件: " + cap2.all());
        assertFalse(cap2.has("ask_user"), "轮 2 不应有 ask_user 事件");
        // BUG 修复断言：tool_confirm 暂停必须终止 SSE 流（done + complete），
        // 否则前端 streamChat 永久 pending、连接泄漏，多轮交互后触发浏览器连接上限
        assertTrue(cap2.completed(), "轮 2 tool_confirm 后 emitter 应终止（防连接泄漏）");
        assertTrue(cap2.has("done"), "轮 2 tool_confirm 后应有 done 事件复位前端流式状态");
        PendingInteraction p2 = him.loadInteraction(sessionId);
        assertEquals("call_2", p2.getPendingToolCallId(), "轮 2 暂停应记录被拦截工具的 toolCallId");

        // ---------- 轮 3：用户批准工具，工具执行后 Agent 再次 askUser（多轮交互） ----------
        scriptAskUser("call_3", "需要哪些新闻来源？");
        SseCapture cap3 = SseCapture.attach(controller.chatStream(request(sessionId, "已批准使用工具", true)));

        await(cap3::completed, "轮 3 工具执行 + 再次 ask_user 后 done + complete");
        verify(toolExecutor, timeout(5000)).execute(eq("builtin:http"),
                argThat(args -> args != null && args.contains("news.example.com")));
        // 设计契约：tool_confirm 恢复路径中工具结果以 ToolExecutionResultMessage 静默回填（不推送 observation 事件）
        assertTrue(cap3.has("ask_user"), "轮 3 批准后应再次 ask_user: " + cap3.all());
        assertTrue(cap3.has("done"), "轮 3 应有 done 事件: " + cap3.all());
        PendingInteraction p3 = him.loadInteraction(sessionId);
        assertNotNull(p3, "轮 3 再次暂停应保存 pending");
        assertEquals(PendingInteraction.MODE_DIRECT, p3.getMode(), "轮 3 暂停模式应为 direct");
        assertEquals(1, p3.getRetryCount(), "轮 3 追问计数应累加为 1");

        // ---------- 轮 4：用户回答"今天"，Agent 输出最终回答 ----------
        scriptStop("以下是今天的新闻快讯：……");
        SseCapture cap4 = SseCapture.attach(controller.chatStream(request(sessionId, "今天", null)));

        await(cap4::completed, "轮 4 最终回答 done + complete");
        assertTrue(cap4.has("token"), "轮 4 应有 token 事件: " + cap4.all());
        assertTrue(cap4.has("done"), "轮 4 应有 done 事件: " + cap4.all());
        assertFalse(him.hasPending(sessionId), "轮 4 完成后 pending 应清除");

        // 最终回答写入会话记忆（多轮上下文不丢失）
        boolean memoryHasFinal = memoryManager.getMemory(sessionId).messages().stream()
                .filter(AiMessage.class::isInstance)
                .map(m -> ((AiMessage) m).text())
                .anyMatch(t -> t != null && t.contains("今天的新闻快讯"));
        assertTrue(memoryHasFinal, "最终回答应写入会话记忆");
    }
}
