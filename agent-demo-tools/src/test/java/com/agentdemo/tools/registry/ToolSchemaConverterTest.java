package com.agentdemo.tools.registry;

import com.agentdemo.tools.builtin.CalculatorTool;
import com.agentdemo.tools.builtin.TimeTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ToolSchemaConverter 测试
 * <p>
 * 验证标准来源：Task-04 验证标准
 * 业务含义：ToolSchemaConverter 负责将 @Tool 注解方法转换为 OpenAI 兼容的 tools JSON Schema，
 * 供 LLM 进行 function calling 时识别可用工具及其参数定义。
 * </p>
 */
class ToolSchemaConverterTest {

    private ToolRegistry mockRegistry;
    private ToolSchemaConverter converter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockRegistry = mock(ToolRegistry.class);
        converter = new ToolSchemaConverter(mockRegistry);
    }

    /**
     * 验证 convertToJson 返回有效的 JSON 数组字符串
     * 业务含义：LLM 需要接收一个 JSON 数组，每个元素描述一个可调用的工具函数
     */
    @Test
    void shouldReturnValidJsonArrayString() throws Exception {
        when(mockRegistry.listTools()).thenReturn(
                List.of(new CalculatorTool(), new TimeTool()));

        String json = converter.convertToJson();

        assertNotNull(json, "convertToJson 不应返回 null");
        JsonNode root = objectMapper.readTree(json);
        assertTrue(root.isArray(), "返回结果应为 JSON 数组");
        assertFalse(root.isEmpty(), "数组不应为空（包含 CalculatorTool 和 TimeTool 的方法）");
    }

    /**
     * 验证每个元素包含 type: "function" 和 function 对象
     * 业务含义：OpenAI function calling 协议要求每个工具元素包含 type 字段和 function 描述对象
     */
    @Test
    void shouldContainTypeFunctionAndFunctionObject() throws Exception {
        when(mockRegistry.listTools()).thenReturn(
                List.of(new CalculatorTool(), new TimeTool()));

        JsonNode root = objectMapper.readTree(converter.convertToJson());

        for (JsonNode element : root) {
            assertTrue(element.has("type"), "每个元素应包含 type 字段");
            assertEquals("function", element.get("type").asText(),
                    "type 字段值应为 'function'");
            assertTrue(element.has("function"), "每个元素应包含 function 对象");
            assertTrue(element.get("function").isObject(), "function 应为对象类型");
        }
    }

    /**
     * 验证 function 对象包含 name（方法名）和 description（@Tool 注解值）
     * 业务含义：LLM 通过 name 识别工具，通过 description 理解工具用途
     */
    @Test
    void shouldContainCorrectNameAndDescription() throws Exception {
        when(mockRegistry.listTools()).thenReturn(
                List.of(new CalculatorTool(), new TimeTool()));

        JsonNode root = objectMapper.readTree(converter.convertToJson());

        // 收集所有方法名，验证 calculate 和 getCurrentTime 都存在
        boolean hasCalculate = false;
        boolean hasGetCurrentTime = false;

        for (JsonNode element : root) {
            JsonNode function = element.get("function");
            assertTrue(function.has("name"), "function 应包含 name 字段");
            assertTrue(function.has("description"), "function 应包含 description 字段");
            assertFalse(function.get("description").asText().isEmpty(),
                    "description 不应为空");

            String name = function.get("name").asText();
            if ("calculate".equals(name)) {
                hasCalculate = true;
                assertEquals("计算数学表达式，支持加减乘除、括号、幂运算(用 ^ 表示幂)，例如：2+3、(2+3)*4、2^10",
                        function.get("description").asText(),
                        "calculate 的 description 应为 @Tool 注解值");
            }
            if ("getCurrentTime".equals(name)) {
                hasGetCurrentTime = true;
                assertEquals("获取当前时间，返回标准格式 yyyy-MM-dd HH:mm:ss",
                        function.get("description").asText(),
                        "getCurrentTime 的 description 应为 @Tool 注解值");
            }
        }

        assertTrue(hasCalculate, "应包含 calculate 方法");
        assertTrue(hasGetCurrentTime, "应包含 getCurrentTime 方法");
    }

    /**
     * 验证 function.parameters 为 JSON Schema 对象，含 type: "object"、properties、required
     */
    @Test
    void shouldContainValidParametersSchema() throws Exception {
        when(mockRegistry.listTools()).thenReturn(
                List.of(new CalculatorTool(), new TimeTool()));

        JsonNode root = objectMapper.readTree(converter.convertToJson());

        for (JsonNode element : root) {
            JsonNode parameters = element.get("function").get("parameters");
            assertNotNull(parameters, "function 应包含 parameters 对象");
            assertEquals("object", parameters.get("type").asText(),
                    "parameters.type 应为 'object'");
            assertTrue(parameters.has("properties"), "parameters 应包含 properties 字段");
            assertTrue(parameters.has("required"), "parameters 应包含 required 字段");
            assertTrue(parameters.get("required").isArray(), "required 应为数组类型");
        }
    }

    /**
     * 验证 CalculatorTool.calculate 的 schema 中 properties 包含 expression（string 类型）
     * 业务含义：LLM 需要知道 calculate 方法需要一个 expression 字符串参数
     */
    @Test
    void calculatorCalculateShouldContainExpressionProperty() throws Exception {
        when(mockRegistry.listTools()).thenReturn(
                List.of(new CalculatorTool()));

        JsonNode root = objectMapper.readTree(converter.convertToJson());

        JsonNode calculateFunction = findFunctionByName(root, "calculate");
        assertNotNull(calculateFunction, "应存在 calculate 方法");

        JsonNode properties = calculateFunction.get("parameters").get("properties");
        assertTrue(properties.has("expression"), "properties 应包含 expression 字段");
        assertEquals("string", properties.get("expression").get("type").asText(),
                "expression 参数类型应为 string");

        // 验证 required 数组包含 expression
        JsonNode required = calculateFunction.get("parameters").get("required");
        assertTrue(containsString(required, "expression"),
                "required 数组应包含 expression");
    }

    /**
     * 验证 TimeTool.getCurrentTime 的 schema 中 properties 为空对象，required 为空数组
     * 业务含义：getCurrentTime 方法无参数，LLM 调用时不需要提供任何参数
     */
    @Test
    void timeGetCurrentTimeShouldHaveEmptyPropertiesAndRequired() throws Exception {
        when(mockRegistry.listTools()).thenReturn(
                List.of(new TimeTool()));

        JsonNode root = objectMapper.readTree(converter.convertToJson());

        JsonNode getCurrentTimeFunction = findFunctionByName(root, "getCurrentTime");
        assertNotNull(getCurrentTimeFunction, "应存在 getCurrentTime 方法");

        JsonNode properties = getCurrentTimeFunction.get("parameters").get("properties");
        assertTrue(properties.isObject(), "properties 应为对象类型");
        assertEquals(0, properties.size(), "getCurrentTime 无参数，properties 应为空对象");

        JsonNode required = getCurrentTimeFunction.get("parameters").get("required");
        assertTrue(required.isArray(), "required 应为数组类型");
        assertEquals(0, required.size(), "getCurrentTime 无参数，required 应为空数组");
    }

    /**
     * 验证 Java 类型映射正确：
     * String -> "string"、int/Integer -> "integer"、double/Double -> "number"、boolean/Boolean -> "boolean"
     * 业务含义：LLM 需要正确的参数类型信息才能生成合法的参数值
     */
    @Test
    void shouldMapJavaTypesCorrectly() throws Exception {
        when(mockRegistry.listTools()).thenReturn(
                List.of(new TypeMappingTestTool()));

        JsonNode root = objectMapper.readTree(converter.convertToJson());

        JsonNode testFunction = findFunctionByName(root, "testTypes");
        assertNotNull(testFunction, "应存在 testTypes 方法");

        JsonNode properties = testFunction.get("parameters").get("properties");

        assertEquals("string", properties.get("strParam").get("type").asText(),
                "String 应映射为 string");
        assertEquals("integer", properties.get("intParam").get("type").asText(),
                "int 应映射为 integer");
        assertEquals("integer", properties.get("integerParam").get("type").asText(),
                "Integer 应映射为 integer");
        assertEquals("number", properties.get("doubleParam").get("type").asText(),
                "double 应映射为 number");
        assertEquals("number", properties.get("doubleObjParam").get("type").asText(),
                "Double 应映射为 number");
        assertEquals("boolean", properties.get("boolParam").get("type").asText(),
                "boolean 应映射为 boolean");
        assertEquals("boolean", properties.get("boolObjParam").get("type").asText(),
                "Boolean 应映射为 boolean");
    }

    /**
     * 验证空工具列表时返回空 JSON 数组
     * 业务含义：无工具注册时不应报错，返回空数组供 LLM 识别
     */
    @Test
    void shouldReturnEmptyArrayWhenNoToolsRegistered() throws Exception {
        when(mockRegistry.listTools()).thenReturn(Collections.emptyList());

        String json = converter.convertToJson();

        JsonNode root = objectMapper.readTree(json);
        assertTrue(root.isArray(), "空列表应返回 JSON 数组");
        assertTrue(root.isEmpty(), "无工具时数组应为空");
    }

    // ==================== convertToDescriptionText 测试（CR-001 Task-17） ====================

    /**
     * 验证 convertToDescriptionText 返回非空字符串
     * 业务含义：系统提示词需要拼接工具描述文本，不能为空
     */
    @Test
    void shouldReturnNonEmptyDescriptionText() {
        when(mockRegistry.listTools()).thenReturn(
                List.of(new CalculatorTool(), new TimeTool()));

        String description = converter.convertToDescriptionText();

        assertNotNull(description, "convertToDescriptionText 不应返回 null");
        assertFalse(description.isEmpty(), "工具描述文本不应为空");
    }

    /**
     * 验证返回的字符串包含前缀和后缀
     * 业务含义：工具描述文本需要引导 LLM 理解这是可用工具列表
     */
    @Test
    void shouldContainPrefixAndSuffix() {
        when(mockRegistry.listTools()).thenReturn(
                List.of(new CalculatorTool(), new TimeTool()));

        String description = converter.convertToDescriptionText();

        assertTrue(description.contains("你可以调用以下工具来获取信息"),
                "工具描述应包含前缀引导语");
        assertTrue(description.contains("当问题需要实时信息或计算时，请主动调用工具"),
                "工具描述应包含后缀引导语");
    }

    /**
     * 验证返回的字符串包含已注册工具的方法名和 @Tool 描述值
     * 业务含义：LLM 需要通过方法名和描述理解每个工具的用途
     */
    @Test
    void shouldContainToolNamesAndDescriptions() {
        when(mockRegistry.listTools()).thenReturn(
                List.of(new CalculatorTool(), new TimeTool()));

        String description = converter.convertToDescriptionText();

        // 验证包含方法名
        assertTrue(description.contains("calculate"), "应包含 calculate 方法名");
        assertTrue(description.contains("getCurrentTime"), "应包含 getCurrentTime 方法名");
        assertTrue(description.contains("getCurrentDate"), "应包含 getCurrentDate 方法名");

        // 验证包含 @Tool 注解描述值
        assertTrue(description.contains("计算数学表达式"),
                "应包含 calculate 的描述");
        assertTrue(description.contains("获取当前时间"),
                "应包含 getCurrentTime 的描述");
        assertTrue(description.contains("获取当前日期"),
                "应包含 getCurrentDate 的描述");
    }

    /**
     * 验证每个工具以 "- {方法名}: {描述}" 格式列出
     * 业务含义：统一的格式便于 LLM 解析和理解工具列表
     */
    @Test
    void shouldFormatAsDashMethodNameColonDescription() {
        when(mockRegistry.listTools()).thenReturn(
                List.of(new CalculatorTool()));

        String description = converter.convertToDescriptionText();

        assertTrue(description.contains("- calculate: "),
                "工具应以 '- {方法名}: {描述}' 格式列出");
    }

    /**
     * 验证无工具注册时仍包含前缀和后缀
     * 业务含义：即使没有工具，提示词结构也应完整，不应报错
     */
    @Test
    void shouldHandleEmptyToolListWithPrefixAndSuffix() {
        when(mockRegistry.listTools()).thenReturn(Collections.emptyList());

        String description = converter.convertToDescriptionText();

        assertNotNull(description, "空工具列表不应返回 null");
        assertTrue(description.contains("你可以调用以下工具来获取信息"),
                "空列表仍应包含前缀引导语");
        assertTrue(description.contains("当问题需要实时信息或计算时，请主动调用工具"),
                "空列表仍应包含后缀引导语");
    }

    // ==================== 辅助方法 ====================

    /**
     * 在 JSON 数组中按方法名查找 function 对象
     */
    private JsonNode findFunctionByName(JsonNode root, String name) {
        for (JsonNode element : root) {
            JsonNode function = element.get("function");
            if (name.equals(function.get("name").asText())) {
                return function;
            }
        }
        return null;
    }

    /**
     * 检查 JSON 数组中是否包含指定字符串
     */
    private boolean containsString(JsonNode array, String value) {
        for (JsonNode node : array) {
            if (value.equals(node.asText())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 类型映射测试工具
     * <p>
     * 业务含义：用于验证 Java 参数类型到 JSON Schema 类型的正确映射
     * </p>
     */
    static class TypeMappingTestTool {
        @Tool("测试类型映射")
        public String testTypes(
                String strParam,
                int intParam,
                Integer integerParam,
                double doubleParam,
                Double doubleObjParam,
                boolean boolParam,
                Boolean boolObjParam) {
            return "ok";
        }
    }
}
