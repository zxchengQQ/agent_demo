package com.agentdemo.mcp.tool;

import com.agentdemo.mcp.client.McpClientEntry;
import com.agentdemo.mcp.client.McpClientRegistry;
import com.agentdemo.mcp.client.McpTransportWrapper;
import com.agentdemo.mcp.entity.McpServerStatus;
import com.agentdemo.observability.TraceCollector;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.McpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MCP 协议层埋点测试（CR-001 Task-21，AC-N08/E05）
 * <p>
 * 业务含义：验证 McpToolExecutor 成功/失败/断线各路径上报 McpCallEvent（原始 serverName/
 * toolName/argsJson/协议耗时/状态，AC-N08），埋点异常不影响执行主流程（AC-E05）。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class McpToolExecutorTraceTest {

    @Mock
    private McpClientRegistry clientRegistry;
    @Mock
    private McpClient mcpClient;
    @Mock
    private McpContentParser contentParser;
    @Mock
    private TraceCollector collector;

    private McpToolExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new McpToolExecutor(clientRegistry, contentParser,
                com.agentdemo.tools.sanitize.ToolOutputSanitizer.disabled(), collector);
    }

    private McpClientEntry connectedEntry(String server, String rawResponse) {
        McpTransportWrapper wrapper = mock(McpTransportWrapper.class);
        org.mockito.Mockito.lenient().when(wrapper.getLastRawResponse()).thenReturn(rawResponse);
        com.agentdemo.mcp.entity.McpServer srv = new com.agentdemo.mcp.entity.McpServer();
        srv.setName(server);
        McpClientEntry entry = new McpClientEntry(srv, mcpClient, wrapper);
        entry.setStatus(McpServerStatus.CONNECTED);
        org.mockito.Mockito.lenient().when(clientRegistry.get(server)).thenReturn(entry);
        return entry;
    }

    @Test
    void success_reportsMcpEvent_withAllFields() {
        String raw = "{\"jsonrpc\":\"2.0\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"晴天\"}]}}";
        connectedEntry("weather", raw);
        when(mcpClient.executeTool(any(ToolExecutionRequest.class))).thenReturn(null);
        when(contentParser.parse(raw)).thenReturn("晴天");

        String result = executor.execute("weather", "getForecast", "{\"city\":\"北京\"}");

        assertThat(result).isEqualTo("晴天");
        ArgumentCaptor<TraceCollector.McpCallEvent> captor =
                ArgumentCaptor.forClass(TraceCollector.McpCallEvent.class);
        verify(collector).recordMcp(captor.capture());
        TraceCollector.McpCallEvent e = captor.getValue();
        assertThat(e.serverName()).isEqualTo("weather");
        assertThat(e.toolName()).isEqualTo("getForecast");
        assertThat(e.arguments()).isEqualTo("{\"city\":\"北京\"}");
        assertThat(e.success()).isTrue();
        assertThat(e.disconnected()).isFalse();
        assertThat(e.durationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void failure_reportsErrorEvent_andStillThrowsToCaller() {
        // Server 不存在：失败路径上报 + 异常仍上抛（AC-E05 不吞异常）
        when(clientRegistry.get("missing")).thenReturn(null);

        assertThatThrownBy(() -> executor.execute("missing", "tool", "{}"))
                .isInstanceOf(com.agentdemo.common.exception.BusinessException.class);

        ArgumentCaptor<TraceCollector.McpCallEvent> captor =
                ArgumentCaptor.forClass(TraceCollector.McpCallEvent.class);
        verify(collector).recordMcp(captor.capture());
        assertThat(captor.getValue().success()).isFalse();
        assertThat(captor.getValue().errorMessage()).contains("MCP Server 不存在");
    }

    @Test
    void disconnected_failure_reportsDisconnectedFlag() {
        connectedEntry("weather", null);
        when(mcpClient.executeTool(any(ToolExecutionRequest.class)))
                .thenThrow(new RuntimeException(new java.io.IOException("connection reset")));

        assertThatThrownBy(() -> executor.execute("weather", "tool", "{}"))
                .isInstanceOf(com.agentdemo.common.exception.BusinessException.class);

        ArgumentCaptor<TraceCollector.McpCallEvent> captor =
                ArgumentCaptor.forClass(TraceCollector.McpCallEvent.class);
        verify(collector).recordMcp(captor.capture());
        assertThat(captor.getValue().success()).isFalse();
        assertThat(captor.getValue().disconnected()).isTrue();
    }

    @Test
    void collectorException_doesNotAffectExecution() {
        // AC-E05：埋点自身异常静默降级
        doThrow(new RuntimeException("collector boom"))
                .when(collector).recordMcp(any(TraceCollector.McpCallEvent.class));
        String raw = "{\"jsonrpc\":\"2.0\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"结果\"}]}}";
        connectedEntry("weather", raw);
        when(mcpClient.executeTool(any(ToolExecutionRequest.class))).thenReturn(null);
        when(contentParser.parse(raw)).thenReturn("结果");

        assertThat(executor.execute("weather", "tool", "{}")).isEqualTo("结果");
    }
}
