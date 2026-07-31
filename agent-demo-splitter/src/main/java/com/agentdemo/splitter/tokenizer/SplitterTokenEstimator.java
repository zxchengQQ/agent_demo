package com.agentdemo.splitter.tokenizer;

import com.agentdemo.common.utils.SimpleTokenEstimator;
import org.springframework.stereotype.Component;

/**
 * 分割用 Token 估算器
 * <p>
 * 委托 SimpleTokenEstimator 进行 Token 估算，用于文档分割时的 Token 数计算。
 * 设计为可实例化（非静态），便于在测试中 Mock。
 */
@Component
public class SplitterTokenEstimator {

    /**
     * 估算文本的 Token 数量
     *
     * @param text 输入文本
     * @return 估算的 Token 数量
     */
    public int estimate(String text) {
        return SimpleTokenEstimator.estimate(text);
    }
}
