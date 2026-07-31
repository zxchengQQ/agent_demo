package com.agentdemo.rag.retriever;

import com.agentdemo.rag.entity.KnowledgeBase;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 知识库动态 Tool 工厂测试
 * <p>
 * 业务含义：验证 ByteBuddy 动态生成的知识库 Tool 类是否满足 LangChain4j 工具要求：
 * 1. 方法上带有 @Tool 注解
 * 2. 方法名符合 kb_{kbId} 规则
 * 3. 方法调用正确委托给 KnowledgeRetrieverTool.searchByKbId
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseToolFactoryTest {

    @Mock
    private KnowledgeRetrieverTool retrieverTool;

    private KnowledgeBaseToolFactory factory;

    @BeforeEach
    void setUp() {
        factory = new KnowledgeBaseToolFactory(retrieverTool);
    }

    @Test
    @DisplayName("生成的 Tool 类方法带有 @Tool 注解")
    void createToolShouldGenerateMethodWithToolAnnotation() throws Exception {
        // given
        KnowledgeBase kb = createKnowledgeBase("kb001", "产品文档");

        // when
        Object tool = factory.createTool(kb);

        // then
        Method method = tool.getClass().getMethod("kb_kb001", String.class);
        Tool toolAnnotation = method.getAnnotation(Tool.class);
        assertNotNull(toolAnnotation, "生成的方法应带有 @Tool 注解");
        String description = String.join(" ", toolAnnotation.value());
        assertTrue(description.contains("产品文档"), "工具描述应包含知识库名称");
        assertTrue(description.contains("检索"), "工具描述应包含'检索'");
    }

    @Test
    @DisplayName("生成的 Tool 方法调用委托给 searchByKbId")
    void createToolShouldDelegateToSearchByKbId() throws Exception {
        // given
        KnowledgeBase kb = createKnowledgeBase("kb001", "产品文档");
        when(retrieverTool.searchByKbId("kb001", "价格")).thenReturn("检索结果");

        // when
        Object tool = factory.createTool(kb);
        Method method = tool.getClass().getMethod("kb_kb001", String.class);
        Object result = method.invoke(tool, "价格");

        // then
        assertEquals("检索结果", result);
    }

    @Test
    @DisplayName("buildToolMethodName 返回 kb_{kbId}")
    void buildToolMethodNameShouldReturnKbPrefixedId() {
        KnowledgeBase kb = createKnowledgeBase("abc123", "测试库");

        String methodName = factory.buildToolMethodName(kb);

        assertEquals("kb_abc123", methodName);
    }

    @Test
    @DisplayName("buildToolClassName 包含 kbId")
    void buildToolClassNameShouldContainKbId() {
        KnowledgeBase kb = createKnowledgeBase("abc123", "测试库");

        String className = factory.buildToolClassName(kb);

        assertEquals("com.agentdemo.rag.retriever.KbTool_abc123", className);
    }

    @Test
    @DisplayName("buildToolDescription 包含知识库名称")
    void buildToolDescriptionShouldContainKnowledgeBaseName() {
        KnowledgeBase kb = createKnowledgeBase("abc123", "产品文档");

        String description = factory.buildToolDescription(kb);

        assertTrue(description.contains("产品文档"), "描述应包含知识库名称");
        assertTrue(description.contains("query"), "描述应说明 query 参数");
    }

    @Test
    @DisplayName("LangChain4j 可识别生成的 Tool 并生成 ToolSpecification")
    void generatedToolShouldBeRecognizedByLangChain4j() {
        // given
        KnowledgeBase kb = createKnowledgeBase("kb001", "产品文档");

        // when
        Object tool = factory.createTool(kb);
        java.util.List<ToolSpecification> specs = ToolSpecifications.toolSpecificationsFrom(tool);

        // then
        assertEquals(1, specs.size(), "应生成 1 个 ToolSpecification");
        ToolSpecification spec = specs.get(0);
        assertEquals("kb_kb001", spec.name(), "工具名应为 kb_kb001");
        assertTrue(spec.description().contains("产品文档"), "工具描述应包含知识库名称");
        assertNotNull(spec.parameters(), "工具参数 schema 不应为空");
        assertTrue(spec.parameters().properties().containsKey("query"), "参数应包含 query");
    }

    /**
     * 创建测试用知识库
     */
    private KnowledgeBase createKnowledgeBase(String id, String name) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(id);
        kb.setName(name);
        return kb;
    }
}
