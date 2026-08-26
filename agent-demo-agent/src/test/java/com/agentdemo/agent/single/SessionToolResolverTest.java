package com.agentdemo.agent.single;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.tools.registry.ToolRegistry;
import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SessionToolResolver 单元测试
 * <p>
 * 验证标准来源：unified-chat-mode 任务规划 Task-03 验证标准 + tool-permission-unification Task-05 验证标准
 * 关联 AC：AC-T01（工具解析管道支撑）、AC-N01（统一路径）、AC-T02（同步路径 ask 过滤）
 * 业务含义：统一模式下直答路径与拆解子任务路径共用同一工具解析管道
 * （会话缓存 + 默认工具合并 + askUser 补入 + 按方法名去重 + 能力声明权限过滤）。
 * 调用方只声明能力：askSupported=true 走 ForStreaming（ask 保留），false 走 ForDirect（deny+ask 剔除）。
 * </p>
 */
class SessionToolResolverTest {

    private ToolRegistry toolRegistry;
    private AgentConfig agentConfig;
    private SessionToolResolver resolver;

    /** 模拟默认工具（含单个 @Tool 方法） */
    private final FakeTimeTool fakeTimeTool = new FakeTimeTool();

    /** 模拟 askUser 工具 */
    private final FakeAskUserTool fakeAskUserTool = new FakeAskUserTool();

    @BeforeEach
    void setUp() {
        toolRegistry = mock(ToolRegistry.class);
        agentConfig = new AgentConfig();
        agentConfig.setEnableLogging(false);
        agentConfig.getTools().setDefaultTools(List.of("builtin:getCurrentTime"));

        // 能力声明双方法 stub：askSupported 两种路径的默认工具均返回 fakeTimeTool
        when(toolRegistry.getDefaultToolsForStreaming(eq(List.of("builtin:getCurrentTime"))))
                .thenReturn(List.of(fakeTimeTool));
        when(toolRegistry.getDefaultToolsForDirect(eq(List.of("builtin:getCurrentTime"))))
                .thenReturn(List.of(fakeTimeTool));
        // askUser 豁免恒 ALLOW，ForStreaming 可解析
        when(toolRegistry.resolveToolsForStreaming(List.of("builtin:askUser")))
                .thenReturn(List.of(fakeAskUserTool));

        resolver = new SessionToolResolver(toolRegistry, agentConfig);
    }

    @Test
    @DisplayName("未指定 toolIds（null）时返回默认工具")
    void resolveSessionTools_null未指定_返回默认工具() {
        List<Object> tools = resolver.resolveSessionTools("sess-1", null);

        assertEquals(List.of(fakeTimeTool), tools, "null 时应仅返回默认工具");
        verify(toolRegistry, never()).resolveToolsForStreaming(any());
        verify(toolRegistry, never()).resolveToolsForDirect(any());
    }

    @Test
    @DisplayName("指定 toolIds 时解析指定工具并合并默认（默认不可排除）")
    void resolveSessionTools_指定工具_解析并合并默认() {
        FakeHttpTool httpTool = new FakeHttpTool();
        when(toolRegistry.resolveToolsForStreaming(eq(List.of("builtin:httpGet"))))
                .thenReturn(List.of(httpTool));

        List<Object> tools = resolver.resolveSessionTools("sess-1", List.of("builtin:httpGet"));

        assertTrue(tools.contains(httpTool), "应包含指定工具");
        assertTrue(tools.contains(fakeTimeTool), "默认工具不可排除，应始终包含");
    }

    @Test
    @DisplayName("首次指定后再次未指定（null）时读取会话缓存，不重复解析")
    void resolveSessionTools_指定后未指定_读取会话缓存() {
        FakeHttpTool httpTool = new FakeHttpTool();
        when(toolRegistry.resolveToolsForStreaming(eq(List.of("builtin:httpGet"))))
                .thenReturn(List.of(httpTool));

        resolver.resolveSessionTools("sess-1", List.of("builtin:httpGet"));
        List<Object> second = resolver.resolveSessionTools("sess-1", null);

        assertTrue(second.contains(httpTool), "缓存生效：后续未指定时应沿用会话绑定工具");
        // 业务含义：会话缓存存储 toolIds 而非工具对象实例，后续轮次基于缓存的 ids 重新解析
        verify(toolRegistry, times(2)).resolveToolsForStreaming(eq(List.of("builtin:httpGet")));
    }

    @Test
    @DisplayName("空 toolIds 清除会话缓存，恢复仅默认工具")
    void resolveSessionTools_空列表_清除缓存恢复默认() {
        FakeHttpTool httpTool = new FakeHttpTool();
        when(toolRegistry.resolveToolsForStreaming(eq(List.of("builtin:httpGet"))))
                .thenReturn(List.of(httpTool));

        resolver.resolveSessionTools("sess-1", List.of("builtin:httpGet"));
        List<Object> cleared = resolver.resolveSessionTools("sess-1", List.of());

        assertFalse(cleared.contains(httpTool), "空列表应清除会话绑定");
        assertEquals(List.of(fakeTimeTool), cleared, "恢复仅默认工具");
    }

    @Test
    @DisplayName("ensureAskUserTool 在不含 askUser 的列表中补入 askUser")
    void ensureAskUserTool_未含askUser_补入() {
        List<Object> tools = resolver.ensureAskUserTool(new java.util.ArrayList<>(List.of(fakeTimeTool)));

        assertTrue(tools.contains(fakeAskUserTool), "应自动补入 askUser 工具");
        assertTrue(tools.contains(fakeTimeTool), "原有工具应保留");
    }

    @Test
    @DisplayName("ensureAskUserTool 对已含 askUser 的列表不重复加入")
    void ensureAskUserTool_已含askUser_不重复() {
        List<Object> tools = resolver.ensureAskUserTool(
                new java.util.ArrayList<>(List.of(fakeTimeTool, fakeAskUserTool)));

        long askUserCount = tools.stream()
                .filter(t -> resolver.findToolMethodNames(t).contains("askUser"))
                .count();
        assertEquals(1, askUserCount, "askUser 工具应只出现一次");
        verify(toolRegistry, never()).resolveToolsForStreaming(List.of("builtin:askUser"));
    }

    @Test
    @DisplayName("按 @Tool 方法名去重：同名工具的不同实例只保留一个")
    void ensureAskUserTool_重复工具实例_按方法名去重() {
        FakeTimeTool duplicateTimeTool = new FakeTimeTool();

        List<Object> tools = resolver.ensureAskUserTool(
                new java.util.ArrayList<>(List.of(fakeTimeTool, duplicateTimeTool, fakeAskUserTool)));

        long timeCount = tools.stream()
                .filter(t -> resolver.findToolMethodNames(t).contains("getCurrentTime"))
                .count();
        assertEquals(1, timeCount, "同名方法的工具实例应去重");
    }

    // ==================== Task-05: 能力声明路径过滤 ====================

    @Test
    @DisplayName("askSupported=false 走 ForDirect（剔除 deny + ask）")
    void askSupportedFalseUsesForDirect() {
        FakeHttpTool httpTool = new FakeHttpTool();
        when(toolRegistry.resolveToolsForDirect(eq(List.of("builtin:httpGet"))))
                .thenReturn(List.of(httpTool));

        List<Object> tools = resolver.resolveSessionTools("sess-1", List.of("builtin:httpGet"), false);

        assertTrue(tools.contains(httpTool), "ForDirect 下 allow 工具仍应保留");
        verify(toolRegistry).resolveToolsForDirect(List.of("builtin:httpGet"));
        verify(toolRegistry, never()).resolveToolsForStreaming(any());
    }

    @Test
    @DisplayName("askSupported=true 走 ForStreaming（保留 ask 级工具）")
    void askSupportedTrueUsesForStreaming() {
        FakeHttpTool httpTool = new FakeHttpTool();
        when(toolRegistry.resolveToolsForStreaming(eq(List.of("builtin:httpGet"))))
                .thenReturn(List.of(httpTool));

        List<Object> tools = resolver.resolveSessionTools("sess-1", List.of("builtin:httpGet"), true);

        assertTrue(tools.contains(httpTool), "ForStreaming 下 ask 级工具应保留");
        verify(toolRegistry).resolveToolsForStreaming(List.of("builtin:httpGet"));
        verify(toolRegistry, never()).resolveToolsForDirect(any());
    }

    @Test
    @DisplayName("原签名（无参重载）行为等同 askSupported=true（向后兼容）")
    void defaultOverloadEqualsAskSupportedTrue() {
        FakeHttpTool httpTool = new FakeHttpTool();
        when(toolRegistry.resolveToolsForStreaming(eq(List.of("builtin:httpGet"))))
                .thenReturn(List.of(httpTool));

        resolver.resolveSessionTools("sess-1", List.of("builtin:httpGet"));

        verify(toolRegistry).resolveToolsForStreaming(List.of("builtin:httpGet"));
    }

    @Test
    @DisplayName("默认工具合并逻辑应用能力过滤（ForDirect 调用 2 次）")
    void defaultMergeAppliesCapabilityFilter() {
        resolver.resolveSessionTools("sess-1", null, false);

        // 业务含义：默认工具同样受能力声明过滤约束，deny/ask 默认工具不注入（null 路径 getDefaultToolsForDirect 被调用 2 次：
        // 1. 无指定工具时解析默认；2. mergeDefaults 合并默认）
        verify(toolRegistry, times(2)).getDefaultToolsForDirect(List.of("builtin:getCurrentTime"));
    }

    // ==================== 测试用工具类（带 @Tool 注解） ====================

    static class FakeTimeTool {
        @Tool("获取当前时间")
        public String getCurrentTime() {
            return "12:00";
        }
    }

    static class FakeAskUserTool {
        @Tool("向用户提问")
        public String askUser(String type, String question) {
            return "";
        }
    }

    static class FakeHttpTool {
        @Tool("HTTP GET 请求")
        public String httpGet(String url) {
            return "{}";
        }
    }
}
