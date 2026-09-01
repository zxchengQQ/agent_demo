package com.agentdemo.evaluation.model;

import java.util.List;

/**
 * 评估数据集（langsmith-observability CR-002，AC-N10）
 * <p>业务含义：版本 + 用例集合的不可变容器，对应技术方案 §7.2 Dataset 要素。</p>
 *
 * @param version 数据集版本（运行元数据，基线对比识别用）
 * @param cases   用例集合（不可变拷贝）
 */
public record EvalDataset(String version, List<EvalCase> cases) {

    public EvalDataset {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }
}
