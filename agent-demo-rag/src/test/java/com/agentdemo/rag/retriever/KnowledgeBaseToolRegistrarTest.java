package com.agentdemo.rag.retriever;

import com.agentdemo.rag.entity.KnowledgeBase;
import com.agentdemo.rag.store.KnowledgeBaseStore;
import com.agentdemo.tools.registry.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库 Tool 注册器测试
 * <p>
 * 业务含义：验证系统启动时批量注册已有知识库 Tool，以及运行时单个知识库创建/删除
 * 触发 Tool 注册/注销的生命周期管理能力（AC-033、AC-034、AC-035）。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseToolRegistrarTest {

    @Mock
    private KnowledgeBaseStore knowledgeBaseStore;

    @Mock
    private KnowledgeBaseToolFactory knowledgeBaseToolFactory;

    @Mock
    private ToolRegistry toolRegistry;

    private KnowledgeBaseToolRegistrar registrar;

    @BeforeEach
    void setUp() {
        registrar = new KnowledgeBaseToolRegistrar(knowledgeBaseStore, knowledgeBaseToolFactory, toolRegistry);
    }

    @Test
    @DisplayName("系统启动时无知识库，不注册任何 Tool")
    void runWithEmptyKnowledgeBasesShouldRegisterNothing() throws Exception {
        when(knowledgeBaseStore.findAll()).thenReturn(List.of());

        registrar.run(null);

        verify(toolRegistry, never()).register(any());
    }

    @Test
    @DisplayName("系统启动时批量注册所有已有知识库 Tool")
    void runShouldRegisterToolsForAllExistingKnowledgeBases() throws Exception {
        KnowledgeBase kb1 = createKnowledgeBase("kb001", "产品文档");
        KnowledgeBase kb2 = createKnowledgeBase("kb002", "运维手册");
        when(knowledgeBaseStore.findAll()).thenReturn(List.of(kb1, kb2));

        Object tool1 = mock(Object.class);
        Object tool2 = mock(Object.class);
        when(knowledgeBaseToolFactory.createTool(kb1)).thenReturn(tool1);
        when(knowledgeBaseToolFactory.createTool(kb2)).thenReturn(tool2);

        registrar.run(null);

        verify(toolRegistry).register(tool1);
        verify(toolRegistry).register(tool2);
    }

    @Test
    @DisplayName("单个知识库注册失败不影响其他知识库注册")
    void runShouldContinueWhenSingleRegistrationFails() throws Exception {
        KnowledgeBase kb1 = createKnowledgeBase("kb001", "产品文档");
        KnowledgeBase kb2 = createKnowledgeBase("kb002", "运维手册");
        when(knowledgeBaseStore.findAll()).thenReturn(List.of(kb1, kb2));

        Object tool2 = mock(Object.class);
        when(knowledgeBaseToolFactory.createTool(kb1))
                .thenThrow(new RuntimeException("生成失败"));
        when(knowledgeBaseToolFactory.createTool(kb2)).thenReturn(tool2);

        registrar.run(null);

        verify(toolRegistry, times(1)).register(tool2);
    }

    @Test
    @DisplayName("registerToolForKb 创建并注册指定知识库的 Tool")
    void registerToolForKbShouldCreateAndRegisterTool() {
        KnowledgeBase kb = createKnowledgeBase("kb001", "产品文档");
        Object tool = mock(Object.class);
        when(knowledgeBaseToolFactory.createTool(kb)).thenReturn(tool);

        registrar.registerToolForKb(kb);

        verify(knowledgeBaseToolFactory).createTool(kb);
        verify(toolRegistry).register(tool);
    }

    @Test
    @DisplayName("registerToolForKb 异常时不注册 Tool 且不抛出")
    void registerToolForKbShouldSwallowException() {
        KnowledgeBase kb = createKnowledgeBase("kb001", "产品文档");
        when(knowledgeBaseToolFactory.createTool(kb))
                .thenThrow(new RuntimeException("生成失败"));

        registrar.registerToolForKb(kb);

        verify(toolRegistry, never()).register(any());
    }

    @Test
    @DisplayName("unregisterToolForKb 按方法名注销指定知识库 Tool")
    void unregisterToolForKbShouldUnregisterToolByMethodName() {
        KnowledgeBase kb = createKnowledgeBase("kb001", "产品文档");
        when(knowledgeBaseStore.findById("kb001")).thenReturn(kb);
        when(knowledgeBaseToolFactory.buildToolMethodName(kb)).thenReturn("kb_kb001");

        registrar.unregisterToolForKb("kb001");

        verify(toolRegistry).unregisterTool("kb_kb001");
    }

    @Test
    @DisplayName("unregisterToolForKb 在知识库不存在时按 kbId 构造方法名注销")
    void unregisterToolForKbWhenKbNotExistsShouldUseKbId() {
        when(knowledgeBaseStore.findById("kb001")).thenReturn(null);

        registrar.unregisterToolForKb("kb001");

        verify(toolRegistry).unregisterTool("kb_kb001");
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
