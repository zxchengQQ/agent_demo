package com.agentdemo.tools.registry;

import com.agentdemo.common.dto.ToolInfo;
import com.agentdemo.tools.builtin.AskUserTool;
import com.agentdemo.tools.permission.ToolPermissionGuard;
import com.agentdemo.tools.permission.ToolPermissionLevel;
import com.agentdemo.tools.permission.ToolPermissionProperties;
import com.agentdemo.tools.permission.ToolPermissionService;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationContext;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.agentdemo.tools.permission.ToolPermissionLevel.ALLOW;
import static com.agentdemo.tools.permission.ToolPermissionLevel.ASK;
import static com.agentdemo.tools.permission.ToolPermissionLevel.DENY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task-02 + Task-04: 能力声明双方法过滤 + 出口统一包装测试
 * <p>
 * 业务含义：验证 ToolRegistry 新增的两个能力声明方法（ForStreaming=可暂停确认 /
 * ForDirect=无暂停能力）的过滤语义——deny 全域剔除，ask 按调用方能力分流，
 * askUser 豁免恒可用。过滤后出口统一调用 ToolPermissionGuard.wrap() 逐工具包装
 * （AC-T04：LangChain4j 直调路径命中包装层拦截器，deny 方法体零触发）。
 * </p>
 */
class ToolRegistryPermissionFilterTest {

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
        ToolPermissionGuard guard = new ToolPermissionGuard(permissionService, new ToolIdResolver());
        registry = new ToolRegistry(appCtx, permissionService, new ToolIdResolver(), guard);
    }

    @Test
    @DisplayName("ForStreaming：deny 剔除、ask 保留、allow 保留")
    void streamingKeepsAskAndAllowExcludesDeny() {
        registry.register(new DenyTool());
        registry.register(new AskTool());
        registry.register(new AllowTool());
        permissionService.setExplicit("builtin:deniedAction", DENY);
        permissionService.registerDefault("builtin:askAction", ASK);
        permissionService.registerDefault("builtin:allowedAction", ALLOW);

        List<String> ids = List.of("builtin:deniedAction", "builtin:askAction", "builtin:allowedAction");
        List<Object> tools = registry.resolveToolsForStreaming(ids);

        assertEquals(2, tools.size(), "ForStreaming 应剔除 deny、保留 ask+allow");
        assertTrue(tools.stream().noneMatch(t -> t instanceof DenyTool), "deny 工具不得注入");
    }

    @Test
    @DisplayName("ForDirect：deny + ask 均剔除，仅保留 allow")
    void directKeepsOnlyAllow() {
        registry.register(new DenyTool());
        registry.register(new AskTool());
        registry.register(new AllowTool());
        permissionService.setExplicit("builtin:deniedAction", DENY);
        permissionService.registerDefault("builtin:askAction", ASK);
        permissionService.registerDefault("builtin:allowedAction", ALLOW);

        List<String> ids = List.of("builtin:deniedAction", "builtin:askAction", "builtin:allowedAction");
        List<Object> tools = registry.resolveToolsForDirect(ids);

        assertEquals(1, tools.size(), "ForDirect 应仅保留 allow");
        assertNotEquals(AllowTool.class, tools.get(0).getClass(), "ForDirect 保留的应为出口包装类型（AC-T04）");
    }

    @Test
    @DisplayName("askUser 豁免恒 ALLOW：两个能力方法均保留（防确认死锁 AC-S03）")
    void askUserExemptInBothCapabilityMethods() {
        registry.register(new AskUserTool());
        // 显式尝试配置为 ask/deny 也应被豁免（ToolPermissionService 特判返回 ALLOW）
        permissionService.setExplicit("builtin:askUser", DENY);

        assertEquals(1, registry.resolveToolsForStreaming(List.of("builtin:askUser")).size(),
                "ForStreaming 应保留 askUser");
        assertEquals(1, registry.resolveToolsForDirect(List.of("builtin:askUser")).size(),
                "ForDirect 也应保留 askUser（豁免不随路径变化）");
    }

    @Test
    @DisplayName("默认工具双方法：ForDirect 剔除 ask、ForStreaming 保留 ask")
    void defaultToolsRespectCapabilityMethods() {
        registry.register(new AskTool());
        registry.register(new AllowTool());
        permissionService.registerDefault("builtin:askAction", ASK);
        permissionService.registerDefault("builtin:allowedAction", ALLOW);

        List<String> defaults = List.of("builtin:askAction", "builtin:allowedAction");
        assertEquals(2, registry.getDefaultToolsForStreaming(defaults).size(),
                "ForStreaming 默认工具应保留 ask+allow");
        assertEquals(1, registry.getDefaultToolsForDirect(defaults).size(),
                "ForDirect 默认工具应仅保留 allow");
    }

    @Test
    @DisplayName("通配符 mcp:* 展开后逐工具按 ForDirect 过滤（deny 剔除、ask 剔除、allow 保留）")
    void wildcardMcpFilteredByCapability() {
        registry.register(new McpAllowTool(), "server-a");
        registry.register(new McpAskTool(), "server-b");
        registry.register(new McpDenyTool(), "server-c");
        // MCP 类别默认 ASK，显式声明三种等级
        permissionService.setExplicit("mcp:server-a", ALLOW);
        permissionService.setExplicit("mcp:server-b", ASK);
        permissionService.setExplicit("mcp:server-c", DENY);

        List<Object> direct = registry.resolveToolsForDirect(List.of("mcp:*"));
        assertEquals(1, direct.size(), "ForDirect 通配符展开后仅保留 allow 的 MCP 工具");
        assertNotEquals(McpAllowTool.class, direct.get(0).getClass(), "ForDirect 保留的应为出口包装类型（AC-T04）");

        List<Object> streaming = registry.resolveToolsForStreaming(List.of("mcp:*"));
        assertEquals(2, streaming.size(), "ForStreaming 通配符展开后保留 allow+ask，剔除 deny");
    }

    @Test
    @DisplayName("通配符 rag:* 展开后按 ForDirect 过滤（deny 剔除、ask 剔除、allow 保留）")
    void wildcardRagFilteredByCapability() {
        registry.register(new RagAllowTool());
        registry.register(new RagAskTool());
        permissionService.setExplicit("rag:kbAllow", ALLOW);
        permissionService.setExplicit("rag:kbAsk", ASK);

        List<Object> direct = registry.resolveToolsForDirect(List.of("rag:*"));
        assertEquals(1, direct.size(), "ForDirect 通配符展开后仅保留 allow 的 rag 工具");
        assertNotEquals(RagAllowTool.class, direct.get(0).getClass(), "ForDirect 保留的应为出口包装类型（AC-T04）");
    }

    // ==================== Task-04：出口统一包装 ====================

    @Test
    @DisplayName("出口统一包装：双方法返回对象均为包装类型（getClass 非原类，AC-T04）")
    void capabilityMethodsReturnWrappedInstances() {
        registry.register(new AllowTool());
        permissionService.registerDefault("builtin:allowedAction", ALLOW);

        List<Object> streaming = registry.resolveToolsForStreaming(List.of("builtin:allowedAction"));
        List<Object> direct = registry.resolveToolsForDirect(List.of("builtin:allowedAction"));

        assertEquals(1, streaming.size());
        assertEquals(1, direct.size());
        // 业务含义：方案 B 包装类是 Object 的子类（非继承工具类），getClass 必非原类（AC-T04 出口统一包装）
        assertNotEquals(AllowTool.class, streaming.get(0).getClass(), "ForStreaming 返回包装类型");
        assertNotEquals(AllowTool.class, direct.get(0).getClass(), "ForDirect 返回包装类型");
    }

    @Test
    @DisplayName("包装对象 ToolSpecification 与原对象一致（LangChain4j 断言，schema 保真）")
    void wrappedToolSpecificationMatchesOriginal() {
        registry.register(new AllowTool());
        permissionService.registerDefault("builtin:allowedAction", ALLOW);

        Object original = registry.getTool("AllowTool");
        Object wrapped = registry.resolveToolsForDirect(List.of("builtin:allowedAction")).get(0);

        // 业务含义：包装层不改变 schema 契约（方法名/描述/参数 schema），LangChain4j 生成的
        // ToolSpecification 应与原对象逐字段一致，LLM 侧感知不到包装层存在。
        ToolSpecification origSpec = ToolSpecifications.toolSpecificationsFrom(original).get(0);
        ToolSpecification wrapSpec = ToolSpecifications.toolSpecificationsFrom(wrapped).get(0);

        assertEquals(origSpec.name(), wrapSpec.name(), "工具名应一致");
        assertEquals(origSpec.description(), wrapSpec.description(), "工具描述应一致");
        assertEquals(origSpec.parameters(), wrapSpec.parameters(), "参数 schema 应一致");
    }

    @Test
    @DisplayName("getAvailableTools 返回 ToolInfo 不受包装影响（管理页字段不变）")
    void availableToolsUnaffectedByWrapping() {
        registry.register(new AllowTool());
        permissionService.registerDefault("builtin:allowedAction", ALLOW);

        List<ToolInfo> infos = registry.getAvailableTools(List.of("builtin:allowedAction"));
        assertEquals(1, infos.size());
        ToolInfo info = infos.get(0);
        assertEquals("builtin:allowedAction", info.getId());
        assertEquals("builtin", info.getCategory());
        assertEquals("allowedAction", info.getName());
        assertEquals(ALLOW.getCode(), info.getPermission());
        assertTrue(info.isDefault(), "默认工具应标记 isDefault");
    }

    // ==================== Task-09：旧 API 收口无旁路 ====================

    @Test
    @DisplayName("旧 API 已删除：单参 resolveTools/getDefaultTools 与 ToolPermissionFilter 枚举不存在（AC-T01 无旁路）")
    void legacyApisRemovedNoBypass() {
        // 业务含义：AC-T01 编译期无绕过入口——单参 resolveTools/getDefaultTools 重载与
        // ToolPermissionFilter 枚举均已删除，工具解析唯一入口为能力声明双方法（ForStreaming/ForDirect）。
        assertThrows(NoSuchMethodException.class, () -> ToolRegistry.class.getMethod("resolveTools", List.class));
        assertThrows(NoSuchMethodException.class, () -> ToolRegistry.class.getMethod("getDefaultTools", List.class));
        assertThrows(ClassNotFoundException.class, () -> Class.forName(
                "com.agentdemo.tools.registry.ToolRegistry$ToolPermissionFilter"));
    }

    // ==================== 测试用工具类 ====================

    /** deny 工具（显式置 DENY） */
    static class DenyTool {
        @Tool("deny 工具")
        public String deniedAction() {
            return "should not run";
        }
    }

    /** ask 工具（默认 ASK） */
    static class AskTool {
        @Tool("ask 工具")
        public String askAction() {
            return "ask";
        }
    }

    /** allow 工具（默认登记 ALLOW） */
    static class AllowTool {
        @Tool("allow 工具")
        public String allowedAction() {
            return "allow";
        }
    }

    /** 模拟 MCP allow 工具 */
    static class McpAllowTool {
        @Tool("MCP allow 工具")
        public String mcp_allowAction() {
            return "mcp-allow";
        }
    }

    /** 模拟 MCP ask 工具 */
    static class McpAskTool {
        @Tool("MCP ask 工具")
        public String mcp_askAction() {
            return "mcp-ask";
        }
    }

    /** 模拟 MCP deny 工具 */
    static class McpDenyTool {
        @Tool("MCP deny 工具")
        public String mcp_denyAction() {
            return "mcp-deny";
        }
    }

    /** 模拟 rag allow 工具 */
    static class RagAllowTool {
        @Tool("rag allow 工具")
        public String kb_kbAllow(String query) {
            return "docs";
        }
    }

    /** 模拟 rag ask 工具 */
    static class RagAskTool {
        @Tool("rag ask 工具")
        public String kb_kbAsk(String query) {
            return "docs";
        }
    }
}
