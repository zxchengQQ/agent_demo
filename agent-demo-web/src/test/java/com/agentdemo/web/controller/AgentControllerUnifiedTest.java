package com.agentdemo.web.controller;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.agent.core.HitlTokenStream;
import com.agentdemo.agent.core.HumanInteractionManager;
import com.agentdemo.agent.core.UnifiedChatStream;
import com.agentdemo.agent.single.PlanAgent;
import com.agentdemo.agent.single.SimpleAgent;
import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.common.result.Result;
import com.agentdemo.memory.session.SessionManager;
import com.agentdemo.memory.shortterm.ChatMemoryManager;
import com.agentdemo.tools.permission.ToolPermissionLevel;
import com.agentdemo.tools.permission.ToolPermissionService;
import com.agentdemo.tools.registry.ToolRegistry;
import com.agentdemo.web.dto.ChatRequest;
import com.agentdemo.web.dto.UpdateToolPermissionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentController 统一路由测试（unified-chat-mode Task-13）
 * <p>
 * 验证标准来源：unified-chat-mode 任务规划 Task-13 验证标准
 * 关联 AC：AC-N01~N04、AC-E02、AC-E03
 * 业务含义：验证统一路由——hasPending 恢复优先（回复不解析 /plan）、/plan 空内容友好提示、
 * /plan 剥离后写记忆（控制指令不进入推理上下文）、普通消息统一模式编排。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class AgentControllerUnifiedTest {

    @Mock
    private SimpleAgent simpleAgent;
    @Mock
    private PlanAgent planAgent;
    @Mock
    private SessionManager sessionManager;
    @Mock
    private ChatMemoryManager memoryManager;
    @Mock
    private ToolRegistry toolRegistry;
    @Mock
    private HumanInteractionManager humanInteractionManager;
    @Mock
    private ToolPermissionService toolPermissionService;

    private AgentConfig agentConfig;
    private AgentController controller;

    @BeforeEach
    void setUp() {
        agentConfig = new AgentConfig();
        controller = new AgentController(simpleAgent, planAgent, sessionManager, memoryManager,
                toolRegistry, agentConfig, humanInteractionManager, toolPermissionService);
        // 业务含义：统一路由工具校验只校验不执行，空 tools 时无需 stub（request.getTools() 为 null）
    }

    // ========== Task-13 验证标准 ==========

    @Test
    @DisplayName("hasPending=true -> 走 resumeUnifiedStream，回复不解析 /plan")
    void hasPending_走恢复路由() {
        when(humanInteractionManager.hasPending("sess-1")).thenReturn(true);
        UnifiedChatStream unifiedStream = new UnifiedChatStream(
                "sess-1", "/plan 这是回复中的文本", null, false, true, null, null,
                null, memoryManager, agentConfig, null, null, null,
                humanInteractionManager, null, null);
        when(planAgent.resumeUnifiedStream("sess-1", "/plan 这是回复中的文本")).thenReturn(unifiedStream);
        when(sessionManager.exists("sess-1")).thenReturn(true);

        ChatRequest request = new ChatRequest();
        request.setSessionId("sess-1");
        request.setMessage("/plan 这是回复中的文本");

        controller.chatStream(request);

        verify(planAgent).resumeUnifiedStream("sess-1", "/plan 这是回复中的文本");
        // 恢复路径不写记忆（用户回复作为 Observation 进入 pending 上下文）
        verify(memoryManager, never()).addUserMessage(anyString(), anyString());
    }

    @Test
    @DisplayName("/plan 空内容 -> 不调用 chatUnifiedStream、不写记忆（AC-E02 由 Controller 处理）")
    void emptyPlanContent_不进入编排() {
        when(humanInteractionManager.hasPending("sess-1")).thenReturn(false);
        when(sessionManager.exists("sess-1")).thenReturn(true);

        ChatRequest request = new ChatRequest();
        request.setSessionId("sess-1");
        request.setMessage("/plan");

        controller.chatStream(request);

        // 空内容直接提示，不创建统一编排、不写记忆
        verify(planAgent, never()).chatUnifiedStream(anyString(), anyString(), isNull(), any(), anyBoolean());
        verify(memoryManager, never()).addUserMessage(anyString(), anyString());
    }

    @Test
    @DisplayName("/plan 强制拆解 -> chatUnifiedStream 收到 forcedBreakdown=true，剥离后内容写记忆")
    void planCommand_强制拆解路由() {
        when(humanInteractionManager.hasPending("sess-1")).thenReturn(false);
        when(sessionManager.exists("sess-1")).thenReturn(true);
        UnifiedChatStream unifiedStream = new UnifiedChatStream(
                "sess-1", "调研竞品", null, true, false, null, null,
                null, memoryManager, agentConfig, null, null, null,
                humanInteractionManager, null, null);
        when(planAgent.chatUnifiedStream(anyString(), anyString(), isNull(), any(), anyBoolean()))
                .thenReturn(unifiedStream);

        ChatRequest request = new ChatRequest();
        request.setSessionId("sess-1");
        request.setMessage("/plan 调研竞品");

        controller.chatStream(request);

        // 剥离 /plan 前缀后的内容写记忆（控制指令不进入记忆）
        verify(memoryManager).addUserMessage("sess-1", "调研竞品");
        // forcedBreakdown=true
        ArgumentCaptor<Boolean> forcedCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(planAgent).chatUnifiedStream(anyString(), anyString(), isNull(), any(), forcedCaptor.capture());
        assertTrue(forcedCaptor.getValue(), "forcedBreakdown 应为 true");
    }

    @Test
    @DisplayName("普通消息 -> chatUnifiedStream 收到 forcedBreakdown=false")
    void normalMessage_统一模式路由() {
        when(humanInteractionManager.hasPending("sess-1")).thenReturn(false);
        when(sessionManager.exists("sess-1")).thenReturn(true);
        UnifiedChatStream unifiedStream = new UnifiedChatStream(
                "sess-1", "你好", null, false, false, null, null,
                null, memoryManager, agentConfig, null, null, null,
                humanInteractionManager, null, null);
        when(planAgent.chatUnifiedStream(anyString(), anyString(), isNull(), any(), anyBoolean()))
                .thenReturn(unifiedStream);

        ChatRequest request = new ChatRequest();
        request.setSessionId("sess-1");
        request.setMessage("你好");

        controller.chatStream(request);

        verify(memoryManager).addUserMessage("sess-1", "你好");
        ArgumentCaptor<Boolean> forcedCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(planAgent).chatUnifiedStream(anyString(), anyString(), isNull(), any(), forcedCaptor.capture());
        assertFalse(forcedCaptor.getValue(), "普通消息 forcedBreakdown 应为 false");
    }

    @Test
    @DisplayName("ChatRequest 已删除三个模式字段（getter 不存在），请求体仅含基础字段")
    void chatRequest_无模式字段() {
        // 通过反射验证三个字段已删除
        boolean hasEnableThinking = hasField("enableThinking");
        boolean hasEnableTaskBreakdown = hasField("enableTaskBreakdown");
        boolean hasEnableHitl = hasField("enableHitl");

        assertFalse(hasEnableThinking, "enableThinking 字段应已删除");
        assertFalse(hasEnableTaskBreakdown, "enableTaskBreakdown 字段应已删除");
        assertFalse(hasEnableHitl, "enableHitl 字段应已删除");
    }

    private boolean hasField(String fieldName) {
        try {
            ChatRequest.class.getDeclaredField(fieldName);
            return true;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }

    @Test
    @DisplayName("统一路由返回非 null SseEmitter")
    void chatStream_返回SseEmitter() {
        when(sessionManager.createSession()).thenReturn("sess-new");
        when(humanInteractionManager.hasPending("sess-new")).thenReturn(false);
        UnifiedChatStream unifiedStream = new UnifiedChatStream(
                "sess-new", "你好", null, false, false, null, null,
                null, memoryManager, agentConfig, null, null, null,
                humanInteractionManager, null, null);
        when(planAgent.chatUnifiedStream(anyString(), anyString(), isNull(), any(), anyBoolean()))
                .thenReturn(unifiedStream);

        ChatRequest request = new ChatRequest();
        request.setMessage("你好");

        assertNotNull(controller.chatStream(request), "chatStream 应返回非 null SseEmitter");
    }

    // ========== Task-13 权限 API 与 tool_confirm 事件（tool-permission-control） ==========

    @Test
    @DisplayName("PUT 合法 toolId + 合法等级 -> 返回成功并持久化生效（AC-N01）")
    void updatePermission_合法请求() {
        UpdateToolPermissionRequest req = new UpdateToolPermissionRequest();
        req.setPermission("deny");

        Result<Void> result = controller.updateToolPermission("builtin:httpGet", req);

        assertTrue(result.isSuccess(), "合法请求应返回成功");
        verify(toolRegistry).resolveToolsForDirect(List.of("builtin:httpGet"));
        ArgumentCaptor<ToolPermissionLevel> captor = ArgumentCaptor.forClass(ToolPermissionLevel.class);
        verify(toolPermissionService).setExplicit(eq("builtin:httpGet"), captor.capture());
        assertEquals(ToolPermissionLevel.DENY, captor.getValue(), "应持久化 deny 等级");
    }

    @Test
    @DisplayName("PUT askUser 工具 -> 抛 PARAM_INVALID，配置不变（AC-S03）")
    void updatePermission_askUser拒绝() {
        UpdateToolPermissionRequest req = new UpdateToolPermissionRequest();
        req.setPermission("deny");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.updateToolPermission(ToolPermissionService.ASK_USER_TOOL_ID, req));

        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode(), "askUser 应返回参数错误");
        verify(toolRegistry, never()).resolveToolsForDirect(anyList());
        verify(toolPermissionService, never()).setExplicit(anyString(), any());
    }

    @Test
    @DisplayName("PUT 非法等级值 -> 抛 PARAM_INVALID（参数校验错误）")
    void updatePermission_非法等级() {
        UpdateToolPermissionRequest req = new UpdateToolPermissionRequest();
        req.setPermission("banana");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.updateToolPermission("builtin:httpGet", req));

        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode(), "非法等级应返回参数错误");
        verify(toolPermissionService, never()).setExplicit(anyString(), any());
    }

    @Test
    @DisplayName("chatStream hasPending 且 toolApproved=true -> 走三参 resume（AC-N03）")
    void hasPending_toolApproved_三参resume() {
        when(humanInteractionManager.hasPending("sess-1")).thenReturn(true);
        when(sessionManager.exists("sess-1")).thenReturn(true);
        UnifiedChatStream unifiedStream = new UnifiedChatStream(
                "sess-1", "批准", null, false, true, null, true,
                null, memoryManager, agentConfig, null, null, null,
                humanInteractionManager, null, null);
        when(planAgent.resumeUnifiedStream("sess-1", "批准", Boolean.TRUE)).thenReturn(unifiedStream);

        ChatRequest request = new ChatRequest();
        request.setSessionId("sess-1");
        request.setMessage("批准");
        request.setToolApproved(Boolean.TRUE);

        controller.chatStream(request);

        verify(planAgent).resumeUnifiedStream("sess-1", "批准", Boolean.TRUE);
    }

    @Test
    @DisplayName("chatStream hasPending 且 toolApproved=null -> 走两参 resume（回归断言）")
    void hasPending_toolApprovedNull_两参resume() {
        when(humanInteractionManager.hasPending("sess-1")).thenReturn(true);
        when(sessionManager.exists("sess-1")).thenReturn(true);
        UnifiedChatStream unifiedStream = new UnifiedChatStream(
                "sess-1", "回复", null, false, true, null, null,
                null, memoryManager, agentConfig, null, null, null,
                humanInteractionManager, null, null);
        when(planAgent.resumeUnifiedStream("sess-1", "回复")).thenReturn(unifiedStream);

        ChatRequest request = new ChatRequest();
        request.setSessionId("sess-1");
        request.setMessage("回复");

        controller.chatStream(request);

        verify(planAgent).resumeUnifiedStream("sess-1", "回复");
    }

    @Test
    @DisplayName("onToolConfirm 回调 -> SSE 发送 tool_confirm 事件，payload 三字段完整，连接不关闭（AC-H01）")
    void onToolConfirm_发送toolConfirm事件() throws Exception {
        when(sessionManager.exists("sess-1")).thenReturn(true);
        when(humanInteractionManager.hasPending("sess-1")).thenReturn(false);
        // 业务含义：chatStream 内部 CompletableFuture.runAsync(unifiedStream::start) 会异步执行真实 start()，
        // 而本测试构造的 unifiedStream 大多字段为 null，start() 会走异常路径触发 onError -> emitter.complete()，
        // 与主线程捕获 tool_confirm 事件产生竞态。故用 spy 包装并 stub start() 为空操作，消除异步副作用，
        // 仅验证 registerUnifiedCallbacks 的回调注册与 SSE 发送链路。
        UnifiedChatStream unifiedStream = spy(new UnifiedChatStream(
                "sess-1", "你好", null, false, false, null, null,
                null, memoryManager, agentConfig, null, null, null,
                humanInteractionManager, null, null));
        doNothing().when(unifiedStream).start();
        when(planAgent.chatUnifiedStream(anyString(), anyString(), isNull(), any(), anyBoolean()))
                .thenReturn(unifiedStream);

        ChatRequest request = new ChatRequest();
        request.setSessionId("sess-1");
        request.setMessage("你好");
        SseEmitter emitter = controller.chatStream(request);

        // 业务含义：注入自定义 handler 捕获 SSE 发送数据（模拟容器初始化后的 emitter）。
        // ResponseBodyEmitter.Handler 为 Spring 包级私有接口，外部包无法直接实现或调用 initialize()，
        // 故通过 JDK Proxy 反射实现该接口，并反射写入 ResponseBodyEmitter.handler 私有字段完成注入。
        List<Object> captured = new ArrayList<>();
        Class<?> handlerClass = Class.forName(
                "org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter$Handler");
        Object handler = Proxy.newProxyInstance(
                handlerClass.getClassLoader(),
                new Class<?>[]{handlerClass},
                (proxy, method, args) -> {
                    // 业务含义：Handler 接口 send 有两种重载——send(Object, MediaType) 与 send(Set<DataWithMediaType>)。
                    // SseEmitter.send(SseEventBuilder) 实际走 send(Set) 重载，需遍历 Set 逐个提取 getData() 文本。
                    // 事件分片形态：事件名行是 String（如 "event:tool_confirm\n"），payload 字段值以 Map 存于
                    // DataWithMediaType，统一序列化为字符串以便断言检索字段值。
                    if ("send".equals(method.getName()) && args != null && args[0] != null) {
                        if (args[0] instanceof java.util.Set<?> set) {
                            for (Object item : set) {
                                try {
                                    java.lang.reflect.Method getData = item.getClass().getMethod("getData");
                                    // DataWithMediaType 为包级私有类，需显式放开访问权限后才能 invoke
                                    getData.setAccessible(true);
                                    Object data = getData.invoke(item);
                                    if (data != null) {
                                        captured.add(data instanceof java.util.Map<?, ?>
                                                ? new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data)
                                                : data.toString());
                                    }
                                } catch (Exception ignored) {
                                    // 提取单个 SSE 片段失败时跳过（容错）
                                }
                            }
                        } else {
                            captured.add(args[0]);
                        }
                    }
                    return null;
                });
        Field handlerField = ResponseBodyEmitter.class.getDeclaredField("handler");
        handlerField.setAccessible(true);
        handlerField.set(emitter, handler);

        // 业务含义：反射触发 registerUnifiedCallbacks 注册的 onToolConfirm 回调（真实链路由 HITLReActStream 拦截 ask 工具时调用）
        Field field = UnifiedChatStream.class.getDeclaredField("onToolConfirm");
        field.setAccessible(true);
        HitlTokenStream.ToolConfirmConsumer consumer =
                (HitlTokenStream.ToolConfirmConsumer) field.get(unifiedStream);
        assertNotNull(consumer, "registerUnifiedCallbacks 应注册 onToolConfirm 回调");
        consumer.accept("call_http", "httpGet", "发送 HTTP GET 请求", "{\"url\":\"https://example.com\"}");

        // payload 三字段完整：SSE 事件拆分为事件名行 + JSON 数据行多个分片，合并后统一断言
        String allEvents = captured.stream().filter(String.class::isInstance)
                .map(String.class::cast)
                .reduce("", String::concat);
        assertTrue(allEvents.contains("tool_confirm"), "事件名应包含 tool_confirm，实际: " + allEvents);
        assertTrue(allEvents.contains("httpGet"), "toolName 字段应完整");
        assertTrue(allEvents.contains("发送 HTTP GET 请求"), "toolDescription 字段应完整");
        assertTrue(allEvents.contains("https://example.com"), "arguments 字段应完整");

        // 业务含义：事件后连接不关闭（与 ask_user 不同，tool_confirm 等待用户操作），再次触发仍可发送
        consumer.accept("call_http", "httpGet", "发送 HTTP GET 请求", "{\"url\":\"https://example.com\"}");
        long confirmCount = captured.stream().filter(String.class::isInstance)
                .map(String.class::cast).filter(s -> s.contains("tool_confirm")).count();
        assertEquals(2, confirmCount, "事件后 emitter 应保持打开，可继续发送事件");
    }
}
