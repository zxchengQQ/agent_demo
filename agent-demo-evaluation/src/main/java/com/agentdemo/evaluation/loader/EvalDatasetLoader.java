package com.agentdemo.evaluation.loader;

import com.agentdemo.common.utils.JsonUtils;
import com.agentdemo.evaluation.model.EvalDataset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 评估数据集加载器（langsmith-observability CR-002 Task-26）
 * <p>
 * 业务含义：解析 JSON 数据集字符串为 {@link EvalDataset}。非法 JSON 抛出明确异常
 * （不静默空集，AC-N10 数据侧）；合法 JSON 缺省字段由 record 紧致构造器补默认值。
 * </p>
 */
public class EvalDatasetLoader {

    private static final Logger log = LoggerFactory.getLogger(EvalDatasetLoader.class);

    /**
     * 解析数据集 JSON 字符串
     *
     * @param json 数据集 JSON
     * @return 数据集模型
     * @throws IllegalArgumentException JSON 非法或结构不匹配时抛出（含字段名便于定位）
     */
    public EvalDataset load(String json) {
        try {
            return JsonUtils.fromJson(json, EvalDataset.class);
        } catch (Exception e) {
            log.warn("评估数据集解析失败: {}", e.getMessage());
            throw new IllegalArgumentException("评估数据集 JSON 非法: " + e.getMessage(), e);
        }
    }
}
