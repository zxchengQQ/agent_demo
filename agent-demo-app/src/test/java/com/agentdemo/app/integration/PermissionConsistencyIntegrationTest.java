package com.agentdemo.app.integration;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.agent.single.SessionToolResolver;
import com.agentdemo.tools.builtin.AskUserTool;
import com.agentdemo.tools.builtin.CalculatorTool;
import com.agentdemo.tools.builtin.HttpTool;
import com.agentdemo.tools.permission.ToolPermissionGuard;
import com.agentdemo.tools.permission.ToolPermissionLevel;
import com.agentdemo.tools.permission.ToolPermissionProperties;
import com.agentdemo.tools.permission.ToolPermissionService;
import com.agentdemo.tools.registry.ToolIdResolver;
import com.agentdemo.tools.registry.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static com.agentdemo.tools.permission.ToolPermissionLevel.ALLOW;
import static com.agentdemo.tools.permission.ToolPermissionLevel.ASK;
import static com.agentdemo.tools.permission.ToolPermissionLevel.DENY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task-19: 行为一致性矩阵集成测试（AC-N01/T02/T03/T04/S01/S03/E01/M01）
 * <p>
 * 业务含义：同一工具在四条解析路径（单 Agent 同步 / 单 Agent 流式 / 工作流非 HITL /
 * 工作流 HITL）下按权限等级（allow/ask/deny）表现必须完全一致——该看不见的看不见
 * （deny 加载期全域剔除）、该拦住的拦住（ask 按路径能力分流、deny 执行期包装层零触发）、
 * 该放行的放行（allow 全域可用）。权限判定权收口在工具域（技术方案 §3.1），
 * 不随外部模块调用方式而差异。
 * </p>
 * <p>
 * 装配策略：真实组件（ToolPermissionService + ToolPermissionGuard + ToolRegistry +
 * SessionToolResolver）+ @TempDir 隔离权限文件，注册内置工具（HttpTool/CalculatorTool/
 * AskUserTool）；四条路径分别对应真实调用方内部使用的解析入口：
 * SimpleAgent.chat（ForDirect）、SimpleAgent.chatStream/UnifiedChatStream（ForStreaming）、
 * AgenticAgentFactory.buildAgent（ForDirect）、AgentExecutor.resolveHitlTools（ForStreaming）。
 * </p>
 */
class PermissionConsistencyIntegrationTest {

    @TempDir
    Path tempDir;

    private ToolPermissionService permissionService;
    private ToolPermissionGuard guard;
    private ToolRegistry registry;
    private SessionToolResolver sessionToolResolver;

    @BeforeEach
    void setUp() {
        ToolPermissionProperties props = new ToolPermissionProperties();
        props.setFilePath(tempDir.resolve("perm.json").toString());
        permissionService = new ToolPermissionService(props);

        // mock ApplicationContext 避免 Spring 扫描，工具经 register 动态注册（隔离测试环境）
        ApplicationContext appCtx = mock(ApplicationContext.class);
        when(appCtx.getBeansWithAnnotation(org.springframework.stereotype.Component.class))
                .thenReturn(Collections.emptyMap());
        ToolIdResolver idResolver = new ToolIdResolver();
        guard = new ToolPermissionGuard(permissionService, idResolver);
        registry = new ToolRegistry(appCtx, permissionService, idResolver, guard);

        registry.register(new HttpTool());
        registry.register(new CalculatorTool());
        registry.register(new AskUserTool());

        // 默认工具置空，避免干扰矩阵断言（SessionToolResolver 会合并默认工具）
        AgentConfig agentConfig = new AgentConfig();
        agentConfig.getTools().setDefaultTools(Collections.emptyList());
        sessionToolResolver = new SessionToolResolver(registry, agentConfig);
    }

    // ==================== 四条解析路径（对应真实调用方内部入口） ====================

    /** 单 Agent 同步路径：SimpleAgent.chat(4 参) → SessionToolResolver(askSupported=false) → ForDirect */
    private List<Object> singleSyncPath() {
        return sessionToolResolver.resolveSessionTools("sync", List.of("builtin:httpGet"), false);
    }

    /** 单 Agent 流式路径：SimpleAgent.chatStream / UnifiedChatStream → SessionToolResolver(askSupported=true) → ForStreaming */
    private List<Object> singleStreamPath() {
        return sessionToolResolver.resolveSessionTools("stream", List.of("builtin:httpGet"), true);
    }

    /** 工作流非 HITL 路径：AgenticAgentFactory.buildAgent → resolveToolsForDirect */
    private List<Object> workflowDirectPath() {
        return registry.resolveToolsForDirect(List.of("builtin:httpGet"));
    }

    /** 工作流 HITL 路径：AgentExecutor.resolveHitlTools → resolveToolsForStreaming */
    private List<Object> workflowHitlPath() {
        return registry.resolveToolsForStreaming(List.of("builtin:httpGet"));
    }

    /** 判断工具列表中是否存在指定 @Tool 方法名（包装类型方法名保真，Spike 已验证） */
    private boolean hasTool(List<Object> tools, String methodName) {
        return tools.stream()
                .flatMap(t -> sessionToolResolver.findToolMethodNames(t).stream())
                .anyMatch(methodName::equals);
    }

    /** 反射调用工具（含包装类型）的公开 @Tool 方法 */
    private String invoke(Object target, String methodName, Object... args) {
        try {
            Class<?>[] paramTypes = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                paramTypes[i] = args[i] != null ? args[i].getClass() : String.class;
            }
            Method method = target.getClass().getMethod(methodName, paramTypes);
            Object result = method.invoke(target, args);
            return result != null ? result.toString() : null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 设置 HttpTool 全部 @Tool 方法（httpGet/httpPost）的权限等级
     * <p>
     * 业务含义：HttpTool 含两个 @Tool 方法，过滤按"任一方法命中即整体剔除"判定——
     * 若只改 httpGet 而 httpPost 保持默认 ASK，ForDirect 路径会连带剔除整个 HttpTool，
     * 干扰对 httpGet 的矩阵断言。故两方法同步设置（聚焦被观察工具的等级语义）。
     * </p>
     */
    private void setHttpLevel(ToolPermissionLevel level) {
        permissionService.setExplicit("builtin:httpGet", level);
        permissionService.setExplicit("builtin:httpPost", level);
    }

    // ==================== 行为一致性矩阵（4 路径 × 3 权限等级） ====================

    @Test
    @DisplayName("矩阵-allow：四路径均注入 httpGet，且 allow 工具委托执行（AC-N01/T02/T04）")
    void matrixAllowAllPathsInjectAndExecute() {
        setHttpLevel(ALLOW);

        assertTrue(hasTool(singleSyncPath(), "httpGet"), "单Agent同步应注入 allow 工具");
        assertTrue(hasTool(singleStreamPath(), "httpGet"), "单Agent流式应注入 allow 工具");
        assertTrue(hasTool(workflowDirectPath(), "httpGet"), "工作流非HITL应注入 allow 工具");
        assertTrue(hasTool(workflowHitlPath(), "httpGet"), "工作流HITL应注入 allow 工具");

        // allow 委托执行：纯计算工具（避免 HttpTool 真实外呼），工作流非 HITL 路径注入后调用返回真实结果
        permissionService.setExplicit("builtin:calculate", ALLOW);
        List<Object> direct = registry.resolveToolsForDirect(List.of("builtin:calculate"));
        assertEquals(1, direct.size(), "allow 的 calculate 应注入");
        assertEquals("2+3 = 5", invoke(direct.get(0), "calculate", "2+3"), "包装对象委托原方法执行");
    }

    @Test
    @DisplayName("矩阵-ask：按路径能力分流——同步/非HITL剔除，流式/HITL保留待确认（AC-E01）")
    void matrixAskSplitsByCapability() {
        permissionService.setExplicit("builtin:httpGet", ASK);

        assertFalse(hasTool(singleSyncPath(), "httpGet"), "单Agent同步无暂停能力应剔除 ask");
        assertTrue(hasTool(singleStreamPath(), "httpGet"), "单Agent流式可暂停应保留 ask");
        assertFalse(hasTool(workflowDirectPath(), "httpGet"), "工作流非HITL无暂停能力应剔除 ask");
        assertTrue(hasTool(workflowHitlPath(), "httpGet"), "工作流HITL可暂停应保留 ask");
    }

    @Test
    @DisplayName("矩阵-deny：四路径均剔除（加载期不可见），包装层调用零触发（AC-T02/T03/S01）")
    void matrixDenyAllPathsExcludedAndZeroTrigger() {
        setHttpLevel(DENY);

        // 加载期全域剔除：deny 工具在四条路径均不可见
        assertFalse(hasTool(singleSyncPath(), "httpGet"), "单Agent同步 deny 不可见");
        assertFalse(hasTool(singleStreamPath(), "httpGet"), "单Agent流式 deny 不可见");
        assertFalse(hasTool(workflowDirectPath(), "httpGet"), "工作流非HITL deny 不可见");
        assertFalse(hasTool(workflowHitlPath(), "httpGet"), "工作流HITL deny 不可见");

        // 执行期零触发（第二道防线，AC-T03）：即使绕过加载期过滤直接包装注入，
        // 包装层按解析时捕获的 DENY 拦截，返回固定拒绝文案且 HttpTool 方法体零调用
        HttpTool spyTool = spy(new HttpTool());
        Object wrapper = guard.wrap(spyTool);
        assertEquals(ToolPermissionGuard.DENY_MESSAGE, invoke(wrapper, "httpGet", "https://example.com"),
                "deny 包装调用返回固定拒绝文案");
        verify(spyTool, never()).httpGet(anyString());
    }

    @Test
    @DisplayName("askUser 恒可用：双能力方法 + 会话解析两路径均保留（豁免不随路径变化，AC-S03）")
    void askUserExemptAllPaths() {
        // 显式尝试配置为 deny 也应被豁免（ToolPermissionService 特判返回 ALLOW）
        permissionService.setExplicit("builtin:askUser", DENY);

        assertTrue(hasTool(registry.resolveToolsForStreaming(List.of("builtin:askUser")), "askUser"),
                "工作流HITL路径应保留 askUser");
        assertTrue(hasTool(registry.resolveToolsForDirect(List.of("builtin:askUser")), "askUser"),
                "工作流非HITL路径也应保留 askUser");
        assertTrue(hasTool(sessionToolResolver.resolveSessionTools("asku1", List.of("builtin:askUser"), true), "askUser"),
                "单Agent流式路径应保留 askUser");
        assertTrue(hasTool(sessionToolResolver.resolveSessionTools("asku2", List.of("builtin:askUser"), false), "askUser"),
                "单Agent同步路径也应保留 askUser");
    }

    @Test
    @DisplayName("AC-M01：工作流解析工具后变更权限配置，本轮已注入工具行为不变（不中途突变）")
    void m01CapturedAtResolveTime() {
        permissionService.setExplicit("builtin:calculate", ALLOW);
        List<Object> injected = registry.resolveToolsForDirect(List.of("builtin:calculate"));
        assertEquals(1, injected.size(), "解析时 allow 应注入 calculate");

        // 变更权限为 deny：本轮已注入的包装实例按解析时捕获的 ALLOW 执行（AC-M01）
        permissionService.setExplicit("builtin:calculate", DENY);
        assertEquals("2+3 = 5", invoke(injected.get(0), "calculate", "2+3"),
                "本轮已注入工具不受运行中权限变更影响");

        // 下一轮解析应用 deny：工具被剔除（变更对下一轮生效）
        assertTrue(registry.resolveToolsForDirect(List.of("builtin:calculate")).isEmpty(),
                "下一轮解析应应用 deny 剔除");
    }
}
