package com.agentdemo.tools.registry;

import com.agentdemo.common.dto.ToolInfo;
import com.agentdemo.tools.builtin.CalculatorTool;
import com.agentdemo.tools.builtin.HttpTool;
import com.agentdemo.tools.permission.DefaultToolPermission;
import com.agentdemo.tools.permission.ToolPermissionGuard;
import com.agentdemo.tools.permission.ToolPermissionLevel;
import com.agentdemo.tools.permission.ToolPermissionProperties;
import com.agentdemo.tools.permission.ToolPermissionService;
import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationContext;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.agentdemo.tools.registry.ToolRegistry.ToolMeta;
import static com.agentdemo.tools.permission.ToolPermissionLevel.ALLOW;
import static com.agentdemo.tools.permission.ToolPermissionLevel.ASK;
import static com.agentdemo.tools.permission.ToolPermissionLevel.DENY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ToolRegistry 权限联动单元测试（Task-05 / Task-06）
 * <p>
 * 业务含义：验证加载期过滤（deny 全路径剔除 / ask 按模式分流）、NONE 原签名向后兼容、
 * getToolMeta 元数据映射、扫描/注册的默认权限登记与注销的权限清理（AC-T01/M01/E01/T03）。
 * </p>
 */
class ToolRegistryPermissionTest {

    @TempDir
    Path tempDir;

    private ToolPermissionService permissionService;
    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        ToolPermissionProperties props = new ToolPermissionProperties();
        props.setFilePath(tempDir.resolve("perm.json").toString());
        permissionService = new ToolPermissionService(props);

        ApplicationContext appCtx = mock(ApplicationContext.class);
        when(appCtx.getBeansWithAnnotation(org.springframework.stereotype.Component.class))
                .thenReturn(java.util.Collections.emptyMap());
        registry = new ToolRegistry(appCtx, permissionService, new ToolIdResolver(),
                new ToolPermissionGuard(permissionService, new ToolIdResolver()));
    }

    // ==================== Task-05: 加载期过滤 ====================

    @Test
    @DisplayName("deny 工具在 ForStreaming 与 ForDirect 均被剔除且不抛异常（静默跳过）")
    void denyToolExcludedInBothCapabilityMethods() {
        registry.register(new DenyTool());
        permissionService.setExplicit("builtin:deniedAction", DENY);

        List<Object> streaming = registry.resolveToolsForStreaming(List.of("builtin:deniedAction"));
        List<Object> direct = registry.resolveToolsForDirect(List.of("builtin:deniedAction"));

        assertTrue(streaming.isEmpty(), "ForStreaming 应剔除 deny 工具");
        assertTrue(direct.isEmpty(), "ForDirect 应剔除 deny 工具");
    }

    @Test
    @DisplayName("ask 工具在 ForStreaming 保留、ForDirect 剔除（AC-T01 能力声明分流）")
    void askToolKeptInStreamingButExcludedInDirect() {
        registry.register(new AskTool());
        permissionService.registerDefault("builtin:askAction", ASK);

        assertEquals(1, registry.resolveToolsForStreaming(List.of("builtin:askAction")).size(),
                "ForStreaming 应保留 ask 工具（有暂停能力）");
        assertTrue(registry.resolveToolsForDirect(List.of("builtin:askAction")).isEmpty(),
                "ForDirect 应剔除 ask 工具（无暂停能力）");
    }

    @Test
    @DisplayName("allow 工具在两种能力方法均保留")
    void allowToolKeptInBothModes() {
        registry.register(new AllowTool());
        permissionService.registerDefault("builtin:allowedAction", ALLOW);

        assertEquals(1, registry.resolveToolsForStreaming(List.of("builtin:allowedAction")).size());
        assertEquals(1, registry.resolveToolsForDirect(List.of("builtin:allowedAction")).size());
    }

    @Test
    @DisplayName("显式选择含 deny 工具时静默剔除，其余工具正常解析且为出口包装类型（AC-M01/T04）")
    void explicitSelectionWithDenyToolSilentlyExcludes() {
        registry.register(new AllowTool());
        registry.register(new DenyTool());
        permissionService.setExplicit("builtin:deniedAction", DENY);

        List<Object> result = assertDoesNotThrow(() ->
                registry.resolveToolsForStreaming(List.of("builtin:allowedAction", "builtin:deniedAction")));

        assertEquals(1, result.size(), "deny 工具被静默剔除，不报错");
        assertNotEquals(AllowTool.class, result.get(0).getClass(), "保留的应为出口包装类型（AC-T04）");
    }

    @Test
    @DisplayName("扫描注册后按注解登记默认等级：builtin:calculate 为 ALLOW")
    void scanRegistersAnnotationDefaultLevel() {
        ApplicationContext appCtx = mock(ApplicationContext.class);
        when(appCtx.getBeansWithAnnotation(org.springframework.stereotype.Component.class))
                .thenReturn(Map.of("calculatorTool", new CalculatorTool()));

        ToolPermissionProperties props = new ToolPermissionProperties();
        props.setFilePath(tempDir.resolve("scan-perm.json").toString());
        ToolPermissionService ps = new ToolPermissionService(props);
        ToolRegistry reg = new ToolRegistry(appCtx, ps, new ToolIdResolver(),
                new ToolPermissionGuard(ps, new ToolIdResolver()));

        reg.listTools(); // 触发懒加载扫描

        assertEquals(ALLOW, ps.getPermission("builtin:calculate"), "注解默认等级应被登记");
    }

    @Test
    @DisplayName("getToolMeta 返回正确 toolId 与描述（builtin:httpGet）")
    void getToolMetaReturnsToolIdAndDescription() {
        registry.register(new HttpTool());

        Optional<ToolMeta> meta = registry.getToolMeta("httpGet");

        assertTrue(meta.isPresent());
        assertEquals("builtin:httpGet", meta.get().toolId());
        assertTrue(meta.get().description().contains("HTTP GET"), "描述应来自 @Tool 注解");
    }

    @Test
    @DisplayName("getToolMeta 对未知方法名返回 Optional.empty，不抛异常")
    void getToolMetaUnknownReturnsEmpty() {
        registry.register(new AllowTool());

        assertTrue(registry.getToolMeta("unknownMethod").isEmpty());
        assertTrue(registry.getToolMeta(null).isEmpty());
        assertTrue(registry.getToolMeta("").isEmpty());
    }

    @Test
    @DisplayName("getAvailableTools 每个 ToolInfo 含 permission 字段且与权限服务裁决一致（AC-H02）")
    void getAvailableTools_携带permission字段() {
        registry.register(new AllowAnnotatedTool()); // 注解默认 ALLOW
        registry.register(new AskTool());            // 无注解兜底 ASK
        registry.register(new DenyTool());           // 显式置为 DENY
        permissionService.setExplicit("builtin:deniedAction", DENY);

        List<ToolInfo> tools = registry.getAvailableTools(List.of());

        assertEquals(3, tools.size());
        for (ToolInfo info : tools) {
            String expectCode = permissionService.getPermission(info.getId()).getCode();
            assertEquals(expectCode, info.getPermission(),
                    "ToolInfo[" + info.getId() + "] 的 permission 应与权限服务裁决一致");
        }
    }

    // ==================== Task-06: 动态注册/注销权限生命周期联动 ====================

    @Test
    @DisplayName("动态注册 mcp 工具后默认权限为 ASK（类别兜底）")
    void registerMcpToolDefaultsToAsk() {
        registry.register(new McpMockTool(), "my-server");

        assertEquals(ASK, permissionService.getPermission("mcp:my-server"), "MCP 外部工具默认 ASK");
    }

    @Test
    @DisplayName("动态注册 rag 工具（知识库代理）后默认权限为 ALLOW（类别兜底）")
    void registerRagToolDefaultsToAllow() {
        registry.register(new RagMockTool());

        assertEquals(ALLOW, permissionService.getPermission("rag:kb123"), "知识库只读工具默认 ALLOW");
    }

    @Test
    @DisplayName("setExplicit 后 unregisterTool 清理显式配置，再次 register 回到注解默认等级")
    void unregisterClearsExplicitAndReRegisterReturnsToDefault() {
        registry.register(new AllowAnnotatedTool());
        permissionService.setExplicit("builtin:annotatedAction", DENY);
        assertEquals(DENY, permissionService.getPermission("builtin:annotatedAction"));

        registry.unregisterTool("annotatedAction");
        // 显式配置与默认登记均被清理，查询回落到兜底 ASK
        assertEquals(ASK, permissionService.getPermission("builtin:annotatedAction"), "注销应清理权限配置");

        registry.register(new AllowAnnotatedTool());
        assertEquals(ALLOW, permissionService.getPermission("builtin:annotatedAction"), "再次注册应回到注解默认等级");
    }

    @Test
    @DisplayName("unregisterTool 清理覆盖工具对象全部 @Tool 方法（多方法工具场景）")
    void unregisterClearsAllMethodsOfMultiMethodTool() {
        registry.register(new PermissionMultiMethodTool());
        permissionService.setExplicit("builtin:methodA", DENY);
        permissionService.setExplicit("builtin:methodB", ALLOW);

        registry.unregisterTool("methodA");

        assertEquals(ASK, permissionService.getPermission("builtin:methodA"), "方法 A 的显式配置应被清理");
        assertEquals(ASK, permissionService.getPermission("builtin:methodB"), "方法 B 的显式配置应被清理");
        assertEquals(ASK, permissionService.getPermission("builtin:methodC"), "未配置的方法 C 保持兜底");
    }

    // ==================== 测试用工具类 ====================

    /** deny 工具（无注解，内置兜底 ASK，测试中显式置为 DENY） */
    static class DenyTool {
        @Tool("deny 工具")
        public String deniedAction() {
            return "should not run";
        }
    }

    /** ask 工具（无注解，内置兜底 ASK） */
    static class AskTool {
        @Tool("ask 工具")
        public String askAction() {
            return "ask";
        }
    }

    /** allow 工具（无注解，测试中显式登记 ALLOW） */
    static class AllowTool {
        @Tool("allow 工具")
        public String allowedAction() {
            return "allow";
        }
    }

    /** 带 ALLOW 注解的内置工具（验证注销后回到注解默认） */
    @DefaultToolPermission(ALLOW)
    static class AllowAnnotatedTool {
        @Tool("allow 注解工具")
        public String annotatedAction() {
            return "ok";
        }
    }

    /** 模拟 MCP 工具（方法名 mcp_ 前缀，注册时带 serverName） */
    static class McpMockTool {
        @Tool("MCP 模拟工具")
        public String mcp_doSomething() {
            return "mcp";
        }
    }

    /** 模拟知识库代理工具（方法名 kb_ 前缀） */
    static class RagMockTool {
        @Tool("知识库检索模拟工具")
        public String kb_kb123(String query) {
            return "docs";
        }
    }

    /** 多方法工具（验证注销清理覆盖全部方法） */
    static class PermissionMultiMethodTool {
        @Tool("方法 A")
        public String methodA() {
            return "A";
        }

        @Tool("方法 B")
        public String methodB() {
            return "B";
        }

        @Tool("方法 C")
        public String methodC() {
            return "C";
        }
    }
}
