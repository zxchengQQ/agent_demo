package com.agentdemo.evaluation.cli;

import com.agentdemo.evaluation.config.EvalProperties;
import com.agentdemo.evaluation.harness.EvaluationHarness;
import com.agentdemo.observability.ObservabilityAutoConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * 评估 CLI（langsmith-observability CR-002 Task-34）
 * <p>
 * 业务含义：手动触发本地评估的独立入口（不随正常应用启动，AC-E05 生态）——
 * 上下文排除 {@link ObservabilityAutoConfiguration}（评估期间以 RecordingTraceCollector
 * 录制工具轨迹，且评估对象是 Agent 本体而非 LangSmith 集成，技术方案 §7.2）。
 * eval.enabled=true 且 eval.run-on-startup=true 时执行一次完整评估后退出。
 * </p>
 */
@SpringBootApplication
@ComponentScan(basePackages = {
        "com.agentdemo.agent", "com.agentdemo.llm", "com.agentdemo.tools",
        "com.agentdemo.memory", "com.agentdemo.skill", "com.agentdemo.evaluation"},
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = ObservabilityAutoConfiguration.class))
public class EvaluationCli implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(EvaluationCli.class);

    private final ObjectProvider<EvaluationHarness> harnessProvider;
    private final EvalProperties props;

    public EvaluationCli(ObjectProvider<EvaluationHarness> harnessProvider, EvalProperties props) {
        this.harnessProvider = harnessProvider;
        this.props = props;
    }

    @Override
    public void run(String... args) {
        if (!props.isEnabled()) {
            log.info("评估 harness 未启用（eval.enabled=false），跳过。");
            return;
        }
        if (!props.isRunOnStartup()) {
            log.info("评估 harness 已装配（eval.enabled=true），未配置 eval.run-on-startup=true，不自动执行。");
            return;
        }
        EvaluationHarness harness = harnessProvider.getIfAvailable();
        if (harness == null) {
            log.warn("评估 harness 不可用（Bean 未装配），跳过。");
            return;
        }
        harness.runOnce();
    }

    public static void main(String[] args) {
        SpringApplication.run(EvaluationCli.class, args);
    }
}
