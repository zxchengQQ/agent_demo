package com.agentdemo.mcp.tool;

import com.agentdemo.mcp.entity.McpToolInfo;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * McpToolFactory ByteBuddy 工具生成器测试
 * <p>
 * 验证来源：Task-12 验证标准
 * 关联 AC：AC-005, AC-025, AC-031
 * </p>
 */
@DisplayName("McpToolFactory ByteBuddy 工具生成器测试")
@ExtendWith(MockitoExtension.class)
class McpToolFactoryTest {

    @Mock
    private McpToolExecutor toolExecutor;

    private McpToolFactory factory;

    @BeforeEach
    void setUp() {
        factory = new McpToolFactory(toolExecutor);
    }

    @Test
    @DisplayName("createTools 返回非空 List，size 等于工具数量")
    void createToolsShouldReturnNonEmptyList() {
        McpToolInfo toolInfo = createToolInfo("getForecast", "weather", "获取天气预报");

        List<Object> tools = factory.createTools("weather", List.of(toolInfo));

        assertNotNull(tools);
        assertEquals(1, tools.size());
    }

    @Test
    @DisplayName("生成的代理类 name 为 com.agentdemo.mcp.tool.McpTool_{serverName}_{toolName}")
    void generatedClassNameShouldFollowNamingConvention() {
        McpToolInfo toolInfo = createToolInfo("getForecast", "weather", "获取天气预报");

        List<Object> tools = factory.createTools("weather", List.of(toolInfo));

        String className = tools.get(0).getClass().getName();
        assertTrue(className.contains("weather"), "类名应包含 serverName");
        assertTrue(className.contains("getForecast"), "类名应包含 toolName");
    }

    @Test
    @DisplayName("生成的代理类包含 public 方法 mcp_weather_getForecast")
    void generatedClassShouldContainPublicMethodWithExpectedName() throws Exception {
        McpToolInfo toolInfo = createToolInfo("getForecast", "weather", "获取天气预报");

        List<Object> tools = factory.createTools("weather", List.of(toolInfo));

        Object tool = tools.get(0);
        String methodName = factory.buildMethodName("weather", "getForecast");
        Method method = tool.getClass().getMethod(methodName, String.class);
        assertEquals("mcp_weather_getForecast", methodName);
    }

    @Test
    @DisplayName("生成的方法带有 @Tool 注解，注解 value 包含 serverName 和 toolName")
    void generatedMethodShouldHaveToolAnnotation() throws Exception {
        McpToolInfo toolInfo = createToolInfo("getForecast", "weather", "获取天气预报");

        List<Object> tools = factory.createTools("weather", List.of(toolInfo));

        Object tool = tools.get(0);
        Method method = tool.getClass().getMethod("mcp_weather_getForecast", String.class);
        Tool toolAnnotation = method.getAnnotation(Tool.class);
        assertNotNull(toolAnnotation, "方法应带有 @Tool 注解");
        String description = String.join(" ", toolAnnotation.value());
        assertTrue(description.contains("weather"), "工具描述应包含 serverName");
        assertTrue(description.contains("getForecast"), "工具描述应包含 toolName");
        assertTrue(description.contains("获取天气预报"), "工具描述应包含原始工具描述");
    }

    @Test
    @DisplayName("调用生成的方法委托给 toolExecutor.execute 并返回结果")
    void generatedMethodShouldDelegateToToolExecutor() throws Exception {
        McpToolInfo toolInfo = createToolInfo("getForecast", "weather", "获取天气预报");
        when(toolExecutor.execute(eq("weather"), eq("getForecast"), eq("{\"city\":\"北京\"}")))
                .thenReturn("晴天 25度");

        List<Object> tools = factory.createTools("weather", List.of(toolInfo));

        Object tool = tools.get(0);
        Method method = tool.getClass().getMethod("mcp_weather_getForecast", String.class);
        Object result = method.invoke(tool, "{\"city\":\"北京\"}");
        assertEquals("晴天 25度", result);
    }

    @Test
    @DisplayName("createTools 生成多个工具，类名和方法名互不相同")
    void createToolsShouldGenerateMultipleDistinctTools() throws Exception {
        McpToolInfo tool1 = createToolInfo("getForecast", "weather", "天气预报");
        McpToolInfo tool2 = createToolInfo("getHistory", "weather", "历史天气");

        List<Object> tools = factory.createTools("weather", List.of(tool1, tool2));

        assertEquals(2, tools.size());
        String className1 = tools.get(0).getClass().getName();
        String className2 = tools.get(1).getClass().getName();
        assertFalse(className1.equals(className2), "两个工具类名应不同");
    }

    @Test
    @DisplayName("不同 Server 的同名工具前缀隔离（验证 AC-025）")
    void differentServersShouldGenerateDistinctToolNames() {
        McpToolInfo searchTool = createToolInfo("search", "github", "搜索代码");

        List<Object> githubTools = factory.createTools("github", List.of(searchTool));
        List<Object> gitlabTools = factory.createTools("gitlab", List.of(searchTool));

        String githubMethodName = factory.buildMethodName("github", "search");
        String gitlabMethodName = factory.buildMethodName("gitlab", "search");

        assertEquals("mcp_github_search", githubMethodName);
        assertEquals("mcp_gitlab_search", gitlabMethodName);
        assertFalse(githubMethodName.equals(gitlabMethodName), "不同 Server 同名工具的方法名应不同");
    }

    @Test
    @DisplayName("createTools 当工具列表为空时返回空 List")
    void createToolsWithEmptyListShouldReturnEmptyList() {
        List<Object> tools = factory.createTools("weather", List.of());

        assertNotNull(tools);
        assertTrue(tools.isEmpty());
    }

    @Test
    @DisplayName("LangChain4j 可识别生成的 Tool 并生成 ToolSpecification")
    void generatedToolShouldBeRecognizedByLangChain4j() {
        McpToolInfo toolInfo = createToolInfo("getForecast", "weather", "获取天气预报");

        List<Object> tools = factory.createTools("weather", List.of(toolInfo));

        List<ToolSpecification> specs = ToolSpecifications.toolSpecificationsFrom(tools.get(0));
        assertEquals(1, specs.size(), "应生成 1 个 ToolSpecification");
        ToolSpecification spec = specs.get(0);
        assertEquals("mcp_weather_getForecast", spec.name(), "工具名应为 mcp_weather_getForecast");
        assertNotNull(spec.description(), "工具描述不应为 null");
        assertNotNull(spec.parameters(), "工具参数 schema 不应为 null");
        assertTrue(spec.parameters().properties().containsKey("argsJson"),
                "参数应包含 argsJson");
    }

    @Test
    @DisplayName("buildMethodName 返回 mcp_{serverName}_{toolName}")
    void buildMethodNameShouldReturnPrefixedName() {
        String methodName = factory.buildMethodName("weather", "getForecast");
        assertEquals("mcp_weather_getForecast", methodName);
    }

    @Test
    @DisplayName("buildMethodName 当 serverName 含中划线时替换为下划线（保证 Java 标识符合法）")
    void buildMethodNameShouldSanitizeSpecialChars() {
        String methodName = factory.buildMethodName("my-server", "getForecast");
        assertEquals("mcp_my_server_getForecast", methodName);
    }

    @Test
    @DisplayName("工具描述应包含参数 JSON Schema（验证 LLM 可理解参数格式）")
    void toolDescriptionShouldContainJsonSchema() throws Exception {
        McpToolInfo toolInfo = createToolInfo("getForecast", "weather", "获取天气预报");
        toolInfo.setParametersSchema("{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}");

        List<Object> tools = factory.createTools("weather", List.of(toolInfo));

        Object tool = tools.get(0);
        Method method = tool.getClass().getMethod("mcp_weather_getForecast", String.class);
        Tool toolAnnotation = method.getAnnotation(Tool.class);
        String description = String.join(" ", toolAnnotation.value());
        assertTrue(description.contains("city"), "工具描述应包含 JSON Schema 中的字段名 city");
    }

    private McpToolInfo createToolInfo(String toolName, String serverName, String description) {
        McpToolInfo toolInfo = new McpToolInfo();
        toolInfo.setOriginalName(toolName);
        toolInfo.setRegisteredName(factory.buildMethodName(serverName, toolName));
        toolInfo.setDescription(description);
        toolInfo.setParametersSchema("{\"type\":\"object\",\"properties\":{\"argsJson\":{\"type\":\"string\"}}}");
        return toolInfo;
    }
}
