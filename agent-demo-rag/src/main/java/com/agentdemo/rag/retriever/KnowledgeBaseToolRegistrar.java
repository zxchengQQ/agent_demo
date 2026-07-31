package com.agentdemo.rag.retriever;

import com.agentdemo.rag.entity.KnowledgeBase;
import com.agentdemo.rag.store.KnowledgeBaseStore;
import com.agentdemo.tools.registry.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识库 Tool 生命周期管理器
 * <p>
 * 业务含义：CR-003 核心组件，负责知识库与 Tool 的生命周期联动：
 * 1. 系统启动时批量注册所有已有知识库 Tool（AC-035）
 * 2. 创建知识库后注册对应 Tool（AC-033）
 * 3. 删除知识库前注销对应 Tool（AC-034）
 * </p>
 * <p>
 * 设计原则：
 * 1. 异常隔离：单个知识库 Tool 注册失败不影响其他知识库和系统启动
 * 2. 幂等：重复注册同一知识库 Tool 由 ToolRegistry 自行处理（追加）
 * 3. 无状态：不缓存 Tool 实例，注销时通过方法名匹配
 * </p>
 */
@Slf4j
@Component
public class KnowledgeBaseToolRegistrar implements ApplicationRunner {

    private final KnowledgeBaseStore knowledgeBaseStore;
    private final KnowledgeBaseToolFactory knowledgeBaseToolFactory;
    private final ToolRegistry toolRegistry;

    public KnowledgeBaseToolRegistrar(KnowledgeBaseStore knowledgeBaseStore,
                                      KnowledgeBaseToolFactory knowledgeBaseToolFactory,
                                      ToolRegistry toolRegistry) {
        this.knowledgeBaseStore = knowledgeBaseStore;
        this.knowledgeBaseToolFactory = knowledgeBaseToolFactory;
        this.toolRegistry = toolRegistry;
    }

    /**
     * 系统启动时批量注册所有已有知识库 Tool
     * <p>
     * 业务含义：应用启动完成后，遍历 KnowledgeBaseStore 中所有知识库，
     * 为每个知识库动态生成 Tool 并注册到 ToolRegistry，确保已存在知识库立即可用。
     * 单个知识库注册失败被捕获并记录，不影响其他知识库注册。
     * </p>
     *
     * @param args 应用启动参数
     */
    @Override
    public void run(ApplicationArguments args) {
        List<KnowledgeBase> knowledgeBases = knowledgeBaseStore.findAll();
        log.info("启动批量注册知识库 Tool，共 {} 个", knowledgeBases.size());
        for (KnowledgeBase kb : knowledgeBases) {
            registerToolForKb(kb);
        }
    }

    /**
     * 为指定知识库注册动态 Tool
     * <p>
     * 业务含义：知识库创建成功后调用，使该知识库立即作为独立 Tool 可被 LLM 选择。
     * 生成异常时记录错误但不抛出，避免影响主业务流程（如创建知识库接口返回）。
     * </p>
     *
     * @param kb 知识库实体
     */
    public void registerToolForKb(KnowledgeBase kb) {
        if (kb == null || kb.getId() == null) {
            log.warn("知识库为空或 ID 为空，跳过 Tool 注册");
            return;
        }
        try {
            Object tool = knowledgeBaseToolFactory.createTool(kb);
            toolRegistry.register(tool);
            log.info("注册知识库 Tool 成功: kbId={}, kbName={}", kb.getId(), kb.getName());
        } catch (Exception e) {
            log.error("注册知识库 Tool 失败: kbId={}, kbName={}", kb.getId(), kb.getName(), e);
        }
    }

    /**
     * 为指定知识库注销动态 Tool
     * <p>
     * 业务含义：知识库删除前调用，移除对应 Tool，避免 LLM 继续选择已删除知识库。
     * 优先按知识库实体构造方法名；若知识库已不存在，则按 kbId 构造默认方法名。
     * </p>
     *
     * @param kbId 知识库 ID
     */
    public void unregisterToolForKb(String kbId) {
        if (kbId == null || kbId.isBlank()) {
            log.warn("知识库 ID 为空，跳过 Tool 注销");
            return;
        }
        String toolMethodName = buildToolMethodName(kbId);
        toolRegistry.unregisterTool(toolMethodName);
        log.info("注销知识库 Tool: kbId={}, methodName={}", kbId, toolMethodName);
    }

    /**
     * 构建知识库 Tool 方法名
     */
    private String buildToolMethodName(String kbId) {
        KnowledgeBase kb = knowledgeBaseStore.findById(kbId);
        if (kb != null) {
            return knowledgeBaseToolFactory.buildToolMethodName(kb);
        }
        return "kb_" + kbId;
    }
}
