package com.agentdemo.evaluation.eval;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * judge Prompt 模板加载（langsmith-observability CR-002 Task-30）
 * <p>
 * 业务含义：从 classpath 加载 judge 评估指令制品（resources/prompts/judge.md），
 * 供 {@link JudgeEvaluator} 渲染（file/jar 双协议经 getResourceAsStream 统一处理，
 * 项目习惯：classpath 单资源用流读取）。制品版本演进由 EDD 迭代维护（Task-30）。
 * </p>
 */
public class JudgePromptTemplate {

    public static final String CLASSPATH_PATH = "prompts/judge.md";

    /**
     * 加载 judge 指令模板
     *
     * @return 模板文本（含 {{input}}/{{response}}/{{toolTrace}} 占位符）
     * @throws IllegalStateException 制品缺失/读取失败（启动即失败，避免评估静默用空模板）
     */
    public static String load() {
        try (InputStream in = JudgePromptTemplate.class.getClassLoader().getResourceAsStream(CLASSPATH_PATH)) {
            if (in == null) {
                throw new IllegalStateException("judge Prompt 制品缺失: classpath:" + CLASSPATH_PATH);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("judge Prompt 制品读取失败: " + CLASSPATH_PATH, e);
        }
    }
}
