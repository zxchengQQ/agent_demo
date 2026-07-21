package com.agentdemo.memory.longterm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 长期记忆空实现
 * <p>
 * 业务含义：本阶段不实现长期记忆，所有方法返回空集合。
 * 后续 RAG 模块实现后，替换为基于 Milvus 的向量记忆实现。
 * </p>
 */
@Component
@ConditionalOnMissingBean(LongTermMemory.class)
public class EmptyLongTermMemory implements LongTermMemory {

    @Override
    public void store(String sessionId, String content, Map<String, Object> metadata) {
        // 空实现，后续替换为 Milvus 向量存储
    }

    @Override
    public List<String> retrieve(String query, int topK) {
        return Collections.emptyList();
    }

    @Override
    public List<String> retrieveBySession(String sessionId, String query, int topK) {
        return Collections.emptyList();
    }
}
