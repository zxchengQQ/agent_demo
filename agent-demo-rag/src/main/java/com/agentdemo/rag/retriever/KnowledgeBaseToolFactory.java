package com.agentdemo.rag.retriever;

import com.agentdemo.rag.entity.KnowledgeBase;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import org.springframework.stereotype.Component;

import java.lang.reflect.Modifier;

/**
 * 知识库动态 Tool 工厂
 * <p>
 * 业务含义：CR-003 核心组件，为每个知识库动态生成独立的 Tool 类，
 * 使 LLM 通过 Function Calling 直接选择具体知识库工具，无需传递知识库名称参数，
 * 从而消除 LLM 幻觉生成未知知识库名称的风险。
 * </p>
 * <p>
 * 生成规则：
 * 1. 类名：com.agentdemo.rag.retriever.KbTool_{kbId}
 * 2. 方法名：kb_{kbId}
 * 3. 方法参数：String query
 * 4. 方法带 @Tool 注解，描述动态包含知识库名称
 * 5. 方法内部委托给 {@link KnowledgeRetrieverTool#searchByKbId(String, String)}
 * </p>
 * <p>
 * 技术选型：使用 ByteBuddy 而非 CGLIB，因为 LangChain4j 要求 Tool 方法上必须有 @Tool 注解，
 * CGLIB 生成的代理方法不会继承父类/接口的注解，而 ByteBuddy 可以在生成方法时直接写入注解。
 * </p>
 */
@Slf4j
@Component
public class KnowledgeBaseToolFactory {

    private final KnowledgeRetrieverTool retrieverTool;

    public KnowledgeBaseToolFactory(KnowledgeRetrieverTool retrieverTool) {
        this.retrieverTool = retrieverTool;
    }

    /**
     * 为指定知识库创建动态 Tool 实例
     * <p>
     * 业务含义：运行时生成一个仅属于该知识库的 Tool 类，kbId 在类生成时绑定，
     * 调用方法时自动委托给核心检索逻辑，LLM 无需关心知识库 ID。
     * </p>
     *
     * @param kb 知识库实体
     * @return Tool 实例
     */
    public Object createTool(KnowledgeBase kb) {
        String methodName = buildToolMethodName(kb);
        String className = buildToolClassName(kb);
        String description = buildToolDescription(kb);

        try {
            Class<?> toolClass = new ByteBuddy()
                    .subclass(Object.class)
                    .name(className)
                    .defineMethod(methodName, String.class, Modifier.PUBLIC)
                    .withParameter(String.class, "query")
                    .intercept(MethodDelegation.to(new SearchInterceptor(kb.getId(), retrieverTool)))
                    .annotateMethod(AnnotationDescription.Builder.ofType(Tool.class)
                            .defineArray("value", description)
                            .build())
                    .make()
                    .load(getClass().getClassLoader())
                    .getLoaded();

            Object tool = toolClass.getDeclaredConstructor().newInstance();
            log.info("生成知识库 Tool: class={}, method={}", className, methodName);
            return tool;
        } catch (Exception e) {
            throw new RuntimeException("生成知识库 Tool 失败, kbId=" + kb.getId(), e);
        }
    }

    /**
     * 构建 Tool 类名
     */
    public String buildToolClassName(KnowledgeBase kb) {
        return "com.agentdemo.rag.retriever.KbTool_" + kb.getId();
    }

    /**
     * 构建 Tool 方法名
     */
    public String buildToolMethodName(KnowledgeBase kb) {
        return "kb_" + kb.getId();
    }

    /**
     * 构建 @Tool 描述
     */
    public String buildToolDescription(KnowledgeBase kb) {
        return "从知识库「" + kb.getName() + "」中检索与用户问题相关的文档片段。" +
                "当用户的问题涉及「" + kb.getName() + "」相关内容时调用此工具。" +
                "参数 query 为检索问题。";
    }

    /**
     * ByteBuddy 方法拦截器
     * <p>
     * 业务含义：将动态生成的方法调用委托给 KnowledgeRetrieverTool.searchByKbId，
     * 在拦截器中绑定 kbId，使方法签名只保留 query 一个参数。
     * </p>
     */
    public static class SearchInterceptor {

        private final String kbId;
        private final KnowledgeRetrieverTool retrieverTool;

        public SearchInterceptor(String kbId, KnowledgeRetrieverTool retrieverTool) {
            this.kbId = kbId;
            this.retrieverTool = retrieverTool;
        }

        /**
         * 拦截动态 Tool 方法调用
         *
         * @param args 方法参数数组（此处仅含 query）
         * @return 检索结果文本
         */
        @RuntimeType
        public String search(@AllArguments Object[] args) {
            String query = (String) args[0];
            return retrieverTool.searchByKbId(kbId, query);
        }
    }
}
