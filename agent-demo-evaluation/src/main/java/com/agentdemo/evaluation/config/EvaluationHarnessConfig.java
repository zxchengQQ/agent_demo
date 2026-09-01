package com.agentdemo.evaluation.config;

import com.agentdemo.agent.single.SimpleAgent;
import com.agentdemo.evaluation.eval.BaselineManager;
import com.agentdemo.evaluation.eval.DeterministicEvaluator;
import com.agentdemo.evaluation.eval.JudgeEvaluator;
import com.agentdemo.evaluation.eval.JudgeModelAccess;
import com.agentdemo.evaluation.eval.JudgePromptTemplate;
import com.agentdemo.evaluation.eval.SpringJudgeModelAccess;
import com.agentdemo.evaluation.harness.EvaluationHarness;
import com.agentdemo.evaluation.harness.ReportWriter;
import com.agentdemo.evaluation.loader.EvalDatasetLoader;
import com.agentdemo.evaluation.runner.AgentInvoker;
import com.agentdemo.evaluation.runner.EvaluationRunner;
import com.agentdemo.evaluation.runner.RecordingTraceCollector;
import com.agentdemo.evaluation.runner.SimpleAgentInvoker;
import com.agentdemo.llm.config.LlmConfigStore;
import com.agentdemo.llm.registry.ModelFactory;
import com.agentdemo.observability.SensitiveDataMasker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 评估 harness 装配（langsmith-observability CR-002 Task-26/34）
 * <p>
 * 业务含义：eval.enabled=true 时装配评估组件。本配置在评估 CLI 上下文（排除
 * ObservabilityAutoConfiguration）中生效——RecordingTraceCollector 作为 TraceCollector
 * 注入埋点方以捕获工具轨迹（AC-N10 工具选择断言的数据来源）；judge 出境复用既有
 * LLM 通道（AC-S07）。默认关闭（eval.enabled=false）时本配置不生效，正常启动零影响。
 * </p>
 */
@Configuration
@ConditionalOnProperty(name = "eval.enabled", havingValue = "true")
public class EvaluationHarnessConfig {

    @Bean
    public SensitiveDataMasker evalMasker(EvalProperties props) {
        return new SensitiveDataMasker(props.getMaskMaxChars());
    }

    /** CLI 独立上下文 LLM 配置种子（真实评估前置：store 为空时注入合成厂商+模型） */
    @Bean
    public LlmConfigSeed llmConfigSeed(LlmConfigStore store, EvalProperties props) {
        return new LlmConfigSeed(store, props);
    }

    /** 评估上下文唯一的 TraceCollector（工具轨迹录制替身） */
    @Bean
    public RecordingTraceCollector recordingTraceCollector() {
        return new RecordingTraceCollector();
    }

    @Bean
    public AgentInvoker agentInvoker(SimpleAgent simpleAgent, EvalProperties props) {
        return new SimpleAgentInvoker(simpleAgent, props.getAgentModelId());
    }

    @Bean
    public EvalDatasetLoader evalDatasetLoader() {
        return new EvalDatasetLoader();
    }

    @Bean
    public EvaluationRunner evaluationRunner(AgentInvoker invoker, RecordingTraceCollector collector) {
        return new EvaluationRunner(invoker, collector);
    }

    @Bean
    public DeterministicEvaluator deterministicEvaluator(SensitiveDataMasker masker) {
        return new DeterministicEvaluator(masker);
    }

    @Bean
    public JudgeModelAccess judgeModelAccess(ModelFactory modelFactory, LlmConfigStore configStore) {
        return new SpringJudgeModelAccess(modelFactory, configStore);
    }

    @Bean
    public JudgeEvaluator judgeEvaluator(JudgeModelAccess access, SensitiveDataMasker masker) {
        // 业务含义：制品缺失启动即失败（fail-fast），避免评估静默用空模板
        return new JudgeEvaluator(access, masker, JudgePromptTemplate.load());
    }

    @Bean
    public BaselineManager baselineManager() {
        return new BaselineManager();
    }

    @Bean
    public ReportWriter reportWriter(EvalProperties props, SensitiveDataMasker masker) {
        return new ReportWriter(props, masker);
    }

    @Bean
    public EvaluationHarness evaluationHarness(EvalProperties props, EvalDatasetLoader loader,
                                               EvaluationRunner runner, DeterministicEvaluator deterministic,
                                               JudgeEvaluator judge, BaselineManager baseline, ReportWriter writer) {
        return new EvaluationHarness(props, loader, runner, deterministic, judge, baseline, writer);
    }
}
