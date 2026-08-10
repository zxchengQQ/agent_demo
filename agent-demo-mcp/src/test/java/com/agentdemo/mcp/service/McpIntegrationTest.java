package com.agentdemo.mcp.service;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.mcp.client.McpClientRegistry;
import com.agentdemo.mcp.client.McpTransportFactory;
import com.agentdemo.mcp.config.McpProperties;
import com.agentdemo.mcp.config.McpTransportType;
import com.agentdemo.mcp.entity.McpServer;
import com.agentdemo.mcp.entity.McpServerStatus;
import com.agentdemo.mcp.tool.McpContentParser;
import com.agentdemo.mcp.tool.McpToolExecutor;
import com.agentdemo.mcp.tool.McpToolFactory;
import com.agentdemo.mcp.tool.McpToolRegistrarImpl;
import com.agentdemo.tools.registry.ToolRegistry;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * MCP 模块集成测试
 * <p>
 * 验证标准来源：Task-17 验证标准
 * 关联 AC：AC-001, AC-005, AC-006, AC-012, AC-013, AC-035
 * </p>
 * <p>
 * 测试策略：手动组装真实组件（非 Mock），用 spy 覆盖 McpServerManager.createMcpClient()
 * 返回 Mock McpClient（隔离真实外部 MCP Server），验证完整链路：
 * McpServerManager -> McpToolRegistrar -> McpToolFactory -> ToolRegistry
 * </p>
 * <p>
 * 包位置说明：本测试放在 com.agentdemo.mcp.service 包下，因为 McpServerManager.createMcpClient()
 * 是 protected 方法，需要同包访问权限才能在 spy 上覆盖。
 * </p>
 */
class McpIntegrationTest {

    private McpClientRegistry clientRegistry;
    private McpTransportFactory transportFactory;
    private McpToolFactory toolFactory;
    private ToolRegistry toolRegistry;
    private McpToolRegistrarImpl registrar;
    private McpProperties mcpProperties;
    private McpServerManager manager;

    @BeforeEach
    void setUp() {
        clientRegistry = new McpClientRegistry();
        transportFactory = mock(McpTransportFactory.class);
        mcpProperties = new McpProperties();
        mcpProperties.setEnabled(true);
        mcpProperties.setDefaultToolTimeout(Duration.ofSeconds(60));

        // 真实 ToolRegistry（Mock ApplicationContext，scanTools 不会找到工具）
        ApplicationContext appCtx = mock(ApplicationContext.class);
        when(appCtx.getBeansWithAnnotation(any())).thenReturn(Collections.emptyMap());
        toolRegistry = new ToolRegistry(appCtx);

        // 真实组件链
        McpToolExecutor executor = new McpToolExecutor(clientRegistry, new McpContentParser());
        toolFactory = new McpToolFactory(executor);
        registrar = new McpToolRegistrarImpl(mcpProperties, null, toolFactory, toolRegistry, clientRegistry);

        // spy manager 覆盖 createMcpClient（protected 方法，同包可访问）
        manager = spy(new McpServerManager(clientRegistry, transportFactory, registrar, mcpProperties));
    }

    /** 创建 Mock McpClient，listTools 返回指定工具 */
    private McpClient createMockClient(String... toolNames) {
        McpClient mockClient = mock(McpClient.class);
        List<ToolSpecification> specs = java.util.Arrays.stream(toolNames)
                .map(name -> ToolSpecification.builder()
                        .name(name)
                        .description("工具 " + name)
                        .build())
                .toList();
        lenient().when(mockClient.listTools()).thenReturn(specs);
        return mockClient;
    }

    /** 创建 stdio McpServer */
    private McpServer createStdioServer(String name) {
        McpServer server = new McpServer();
        server.setName(name);
        server.setTransport(McpTransportType.STDIO);
        server.setEnabled(true);
        server.setCommand("node");
        return server;
    }

    /** 创建 sse McpServer */
    private McpServer createSseServer(String name) {
        McpServer server = new McpServer();
        server.setName(name);
        server.setTransport(McpTransportType.SSE);
        server.setEnabled(true);
        server.setUrl("https://example.com/sse");
        return server;
    }

    /** 桩 transportFactory + createMcpClient */
    private void stubConnection(McpClient mockClient) {
        when(transportFactory.createTransport(any())).thenReturn(mock(McpTransport.class));
        doReturn(mockClient).when(manager).createMcpClient(any(), any(), any());
    }

    @Test
    @DisplayName("连接成功后 ToolRegistry 包含 mcp_{serverName}_{toolName} 命名的工具")
    void connect_shouldRegisterToolsToToolRegistry() throws Exception {
        // given
        McpServer weather = createStdioServer("weather");
        McpClient mockClient = createMockClient("getForecast", "getHistory");
        stubConnection(mockClient);

        // when
        McpServer result = manager.connect(weather);

        // then: ToolRegistry 包含 MCP 工具
        int toolCount = toolRegistry.getToolCount();
        assertEquals(2, toolCount, "ToolRegistry 应包含 2 个 MCP 工具");

        // 工具名符合 mcp_{serverName}_{toolName} 规则
        List<Object> tools = toolRegistry.listTools();
        assertTrue(hasToolMethod(tools, "mcp_weather_getForecast"), "应包含 mcp_weather_getForecast 工具");
        assertTrue(hasToolMethod(tools, "mcp_weather_getHistory"), "应包含 mcp_weather_getHistory 工具");

        // Server 状态为 CONNECTED
        assertEquals(McpServerStatus.CONNECTED, result.getStatus());
        assertEquals(McpServerStatus.CONNECTED, clientRegistry.get("weather").getStatus());
    }

    @Test
    @DisplayName("删除 Server 后工具从 ToolRegistry 注销")
    void deleteServer_shouldUnregisterToolsFromToolRegistry() {
        // given: 先连接
        McpServer weather = createStdioServer("weather");
        McpClient mockClient = createMockClient("getForecast");
        stubConnection(mockClient);
        manager.connect(weather);
        assertEquals(1, toolRegistry.getToolCount());

        // when: 删除 Server
        manager.deleteServer("weather");

        // then: 工具被注销
        assertEquals(0, toolRegistry.getToolCount(), "删除后 ToolRegistry 应无 MCP 工具");
        assertNull(clientRegistry.get("weather"), "Registry 中不应再有 weather entry");
    }

    @Test
    @DisplayName("stdio 和 sse 两种传输方式的 Server 同时加载成功")
    void bothTransports_canLoadSimultaneously() {
        // given
        McpServer stdioServer = createStdioServer("weather");
        McpServer sseServer = createSseServer("fetch");
        McpClient stdioClient = createMockClient("getForecast");
        McpClient sseClient = createMockClient("fetchUrl");
        when(transportFactory.createTransport(any())).thenReturn(mock(McpTransport.class));
        doReturn(stdioClient).when(manager).createMcpClient(eq("weather"), any(), any());
        doReturn(sseClient).when(manager).createMcpClient(eq("fetch"), any(), any());

        // when
        manager.connect(stdioServer);
        manager.connect(sseServer);

        // then: 两个 Server 都连接成功，工具都注册
        assertEquals(2, toolRegistry.getToolCount(), "应包含 2 个工具（各 Server 1 个）");
        assertNotNull(clientRegistry.get("weather"));
        assertNotNull(clientRegistry.get("fetch"));
        assertEquals(McpServerStatus.CONNECTED, clientRegistry.get("weather").getStatus());
        assertEquals(McpServerStatus.CONNECTED, clientRegistry.get("fetch").getStatus());
    }

    @Test
    @DisplayName("MCP 工具代理方法带有 @Tool 注解，可被 LangChain4j 识别")
    void generatedToolShouldHaveToolAnnotation() throws Exception {
        // given
        McpServer weather = createStdioServer("weather");
        McpClient mockClient = createMockClient("getForecast");
        stubConnection(mockClient);
        manager.connect(weather);

        // when: 获取生成的工具
        List<Object> tools = toolRegistry.listTools();
        Object mcpTool = tools.stream()
                .filter(t -> hasToolMethod(t, "mcp_weather_getForecast"))
                .findFirst()
                .orElseThrow();

        // then: 方法带 @Tool 注解
        Method method = mcpTool.getClass().getMethod("mcp_weather_getForecast", String.class);
        Tool toolAnnotation = method.getAnnotation(Tool.class);
        assertNotNull(toolAnnotation, "MCP 工具方法应带 @Tool 注解");
        String description = String.join(" ", toolAnnotation.value());
        assertTrue(description.contains("weather"), "工具描述应包含 serverName");
        assertTrue(description.contains("getForecast"), "工具描述应包含 toolName");
    }

    @Test
    @DisplayName("mcp.enabled=false 时模块不加载")
    void whenDisabled_shouldNotLoad() {
        mcpProperties.setEnabled(false);
        registrar.run(null);
        assertEquals(0, toolRegistry.getToolCount(), "禁用时不应有 MCP 工具");
        assertEquals(0, clientRegistry.list().size(), "禁用时不应有 Server entry");
    }

    @Test
    @DisplayName("Server 连接失败时不注册工具，ToolRegistry 不受污染")
    void connectFailed_shouldNotRegisterTools() {
        McpServer weather = createStdioServer("weather");
        when(transportFactory.createTransport(any())).thenReturn(mock(McpTransport.class));
        doThrow(new RuntimeException("连接失败")).when(manager).createMcpClient(any(), any(), any());

        assertThrows(BusinessException.class, () -> manager.connect(weather));
        assertEquals(0, toolRegistry.getToolCount(), "连接失败时不应注册工具");
    }

    /** 检查工具列表中是否有指定名称的 @Tool 方法 */
    private boolean hasToolMethod(List<Object> tools, String methodName) {
        return tools.stream().anyMatch(t -> hasToolMethod(t, methodName));
    }

    /** 检查对象是否有指定名称的 @Tool 方法 */
    private boolean hasToolMethod(Object tool, String methodName) {
        for (Method method : tool.getClass().getDeclaredMethods()) {
            if (method.isAnnotationPresent(Tool.class) && method.getName().equals(methodName)) {
                return true;
            }
        }
        return false;
    }
}
