package com.agentdemo.mcp.tool;

import com.agentdemo.mcp.client.McpClientEntry;
import com.agentdemo.mcp.client.McpClientRegistry;
import com.agentdemo.mcp.config.McpProperties;
import com.agentdemo.mcp.config.McpTransportType;
import com.agentdemo.mcp.entity.McpServer;
import com.agentdemo.mcp.entity.McpServerStatus;
import com.agentdemo.mcp.entity.McpToolInfo;
import com.agentdemo.mcp.service.McpServerManager;
import com.agentdemo.tools.registry.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * McpToolRegistrarImpl 单元测试
 * <p>
 * 验证标准来源：Task-14 验证标准（共 6 项）
 * 关联 AC：AC-001, AC-014, AC-022, AC-032
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class McpToolRegistrarImplTest {

    @Mock
    private McpServerManager serverManager;
    @Mock
    private McpToolFactory toolFactory;
    @Mock
    private ToolRegistry toolRegistry;
    @Mock
    private McpClientRegistry clientRegistry;

    private McpProperties mcpProperties;
    private McpToolRegistrarImpl registrar;

    @BeforeEach
    void setUp() {
        mcpProperties = new McpProperties();
        registrar = new McpToolRegistrarImpl(
                mcpProperties, serverManager, toolFactory, toolRegistry, clientRegistry);
    }

    // ==================== 辅助方法 ====================

    /** 创建 stdio ServerConfig */
    private McpProperties.ServerConfig createStdioConfig(String name, boolean enabled) {
        McpProperties.ServerConfig config = new McpProperties.ServerConfig();
        config.setName(name);
        config.setTransport(McpTransportType.STDIO);
        config.setEnabled(enabled);
        config.setCommand("node");
        config.setArgs(List.of("/path/to/server.js"));
        return config;
    }

    /** 创建含工具的 entry（用于 registerTools/unregisterTools 测试） */
    private McpClientEntry createEntryWithTools(String serverName, String... toolNames) {
        McpServer server = new McpServer();
        server.setName(serverName);
        List<McpToolInfo> tools = new ArrayList<>();
        for (String toolName : toolNames) {
            McpToolInfo info = new McpToolInfo();
            info.setOriginalName(toolName);
            info.setRegisteredName("mcp_" + serverName + "_" + toolName);
            tools.add(info);
        }
        server.setTools(tools);
        McpClientEntry entry = new McpClientEntry(server, null, null);
        entry.setStatus(McpServerStatus.CONNECTED);
        return entry;
    }

    // ==================== run() 启动加载组 ====================

    @Nested
    @DisplayName("run() 启动加载静态 Server")
    class RunTest {

        @Test
        @DisplayName("enabled=true 且 servers 非空时：调用 manager.connect 加载每个 Server")
        void run_whenEnabledAndServersNotEmpty_shouldConnectEach() {
            // given
            mcpProperties.setEnabled(true);
            mcpProperties.setServers(List.of(
                    createStdioConfig("weather", true),
                    createStdioConfig("github", true)));

            // when
            registrar.run(null);

            // then: 对每个 Server 调用 connect
            verify(serverManager, times(2)).connect(any(McpServer.class));
        }

        @Test
        @DisplayName("enabled=false 时：跳过整个加载流程，不调用 manager.connect")
        void run_whenDisabled_shouldSkipAll() {
            // given
            mcpProperties.setEnabled(false);
            mcpProperties.setServers(List.of(createStdioConfig("weather", true)));

            // when
            registrar.run(null);

            // then: 不调用 connect
            verify(serverManager, never()).connect(any());
        }

        @Test
        @DisplayName("某个 Server enabled=false 时：跳过该 Server（标记 DISABLED 存入 Registry），继续加载其他")
        void run_whenServerDisabled_shouldSkipAndContinue() {
            // given
            mcpProperties.setEnabled(true);
            mcpProperties.setServers(List.of(
                    createStdioConfig("weather", true),
                    createStdioConfig("disabled-server", false)));

            // when
            registrar.run(null);

            // then: 只对 enabled=true 的 Server 调用 connect
            verify(serverManager, times(1)).connect(argThat(s -> "weather".equals(s.getName())));
            // DISABLED Server 存入 registry 供 list 查询
            ArgumentCaptor<McpClientEntry> entryCaptor = ArgumentCaptor.forClass(McpClientEntry.class);
            verify(clientRegistry).put(eq("disabled-server"), entryCaptor.capture());
            assertEquals(McpServerStatus.DISABLED, entryCaptor.getValue().getStatus());
        }

        @Test
        @DisplayName("某个 Server 加载失败时：记录日志，继续加载其他 Server，不抛出异常")
        void run_whenConnectFails_shouldContinueOthers() {
            // given
            mcpProperties.setEnabled(true);
            mcpProperties.setServers(List.of(
                    createStdioConfig("fail-server", true),
                    createStdioConfig("ok-server", true)));
            // 第一个 Server 连接失败
            when(serverManager.connect(argThat(s -> "fail-server".equals(s.getName()))))
                    .thenThrow(new RuntimeException("连接被拒绝"));

            // when: 不抛异常
            assertDoesNotThrow(() -> registrar.run(null));

            // then: 两个 Server 都尝试了 connect（失败的不阻塞后续）
            verify(serverManager, times(2)).connect(any(McpServer.class));
        }

        @Test
        @DisplayName("servers 为空列表时：不调用 connect，正常结束")
        void run_whenServersEmpty_shouldDoNothing() {
            mcpProperties.setEnabled(true);
            mcpProperties.setServers(List.of());
            registrar.run(null);
            verify(serverManager, never()).connect(any());
        }
    }

    // ==================== registerTools 组 ====================

    @Nested
    @DisplayName("registerTools() 注册工具到 ToolRegistry")
    class RegisterToolsTest {

        @Test
        @DisplayName("调用 toolFactory.createTools 生成代理类，对每个工具调用 toolRegistry.register")
        void registerTools_shouldCreateAndRegister() {
            // given
            McpClientEntry entry = createEntryWithTools("weather", "getForecast", "getHistory");
            // toolFactory 返回 2 个代理对象
            Object proxy1 = new Object();
            Object proxy2 = new Object();
            when(toolFactory.createTools("weather", entry.getServer().getTools()))
                    .thenReturn(List.of(proxy1, proxy2));

            // when
            registrar.registerTools(entry);

            // then: 2 个工具都注册了（生产代码走带 serverName 的 2 参重载，用于登记 mcp:{serverName} 标识）
            verify(toolFactory).createTools("weather", entry.getServer().getTools());
            verify(toolRegistry).register(proxy1, "weather");
            verify(toolRegistry).register(proxy2, "weather");
        }

        @Test
        @DisplayName("工具列表为空时：不调用 toolFactory，不调用 toolRegistry")
        void registerTools_whenNoTools_shouldDoNothing() {
            McpClientEntry entry = createEntryWithTools("empty");
            // entry 有 0 个工具
            registrar.registerTools(entry);
            // 无工具时直接跳过，不调用 toolFactory
            verify(toolFactory, never()).createTools(any(), any());
            verify(toolRegistry, never()).register(any());
        }
    }

    // ==================== unregisterTools 组 ====================

    @Nested
    @DisplayName("unregisterTools() 从 ToolRegistry 注销工具")
    class UnregisterToolsTest {

        @Test
        @DisplayName("对 entry 中每个工具调用 toolRegistry.unregisterTool(registeredName)")
        void unregisterTools_shouldUnregisterByName() {
            // given
            McpClientEntry entry = createEntryWithTools("weather", "getForecast", "getHistory");

            // when
            registrar.unregisterTools(entry);

            // then: 按 registeredName 注销
            verify(toolRegistry).unregisterTool("mcp_weather_getForecast");
            verify(toolRegistry).unregisterTool("mcp_weather_getHistory");
        }

        @Test
        @DisplayName("工具列表为空时：不调用 toolRegistry.unregisterTool")
        void unregisterTools_whenNoTools_shouldDoNothing() {
            McpClientEntry entry = createEntryWithTools("empty");
            registrar.unregisterTools(entry);
            verify(toolRegistry, never()).unregisterTool(any());
        }
    }
}
