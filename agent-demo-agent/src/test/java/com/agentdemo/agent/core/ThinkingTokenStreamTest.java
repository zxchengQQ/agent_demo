package com.agentdemo.agent.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * ThinkingTokenStream 接口扩展测试
 * <p>
 * 验证标准来源：Task-03 验证标准
 * 业务含义：为显式 ReAct 多轮循环新增 4 个链式回调（推理片段/工具调用/工具结果/最终回答），
 * 每个回调携带 iteration 参数以区分多轮迭代。
 * </p>
 */
class ThinkingTokenStreamTest {

    /**
     * 测试用实现类：实现 ThinkingTokenStream 全部方法，保存各回调引用以供测试触发。
     * 业务含义：仅用于验证接口契约（方法存在、链式返回、回调签名），不涉及真实流式逻辑。
     */
    static class TestThinkingTokenStream implements ThinkingTokenStream {
        ThinkingConsumer thinkingConsumer;
        ResponseConsumer responseConsumer;
        CompleteConsumer completeConsumer;
        ErrorConsumer errorConsumer;
        ThoughtConsumer thoughtConsumer;
        ActionConsumer actionConsumer;
        ObservationConsumer observationConsumer;
        FinalAnswerConsumer finalAnswerConsumer;

        @Override
        public ThinkingTokenStream onPartialThinking(ThinkingConsumer consumer) {
            this.thinkingConsumer = consumer;
            return this;
        }

        @Override
        public ThinkingTokenStream onPartialResponse(ResponseConsumer consumer) {
            this.responseConsumer = consumer;
            return this;
        }

        @Override
        public ThinkingTokenStream onComplete(CompleteConsumer consumer) {
            this.completeConsumer = consumer;
            return this;
        }

        @Override
        public ThinkingTokenStream onError(ErrorConsumer consumer) {
            this.errorConsumer = consumer;
            return this;
        }

        @Override
        public ThinkingTokenStream onPartialThought(ThoughtConsumer consumer) {
            this.thoughtConsumer = consumer;
            return this;
        }

        @Override
        public ThinkingTokenStream onAction(ActionConsumer consumer) {
            this.actionConsumer = consumer;
            return this;
        }

        @Override
        public ThinkingTokenStream onObservation(ObservationConsumer consumer) {
            this.observationConsumer = consumer;
            return this;
        }

        @Override
        public ThinkingTokenStream onFinalAnswer(FinalAnswerConsumer consumer) {
            this.finalAnswerConsumer = consumer;
            return this;
        }

        @Override
        public void start() {
            // 测试桩实现，不执行真实流式逻辑
        }
    }

    /**
     * 验证标准：onPartialThought(ThoughtConsumer) 方法存在且返回 this（链式）；
     * ThoughtConsumer.accept(String thought, int iteration) 签名正确
     */
    @Test
    void onPartialThoughtShouldBeChainableAndCallbackReceivesThoughtAndIteration() {
        TestThinkingTokenStream stream = new TestThinkingTokenStream();

        // 使用数组捕获回调参数（lambda 闭包需 effectively final 外部变量）
        String[] capturedThought = {null};
        int[] capturedIteration = {-1};

        ThinkingTokenStream returned = stream.onPartialThought((thought, iteration) -> {
            capturedThought[0] = thought;
            capturedIteration[0] = iteration;
        });

        // 验证链式返回
        assertSame(stream, returned, "onPartialThought 应返回 this 以支持链式调用");

        // 触发回调，验证 ThoughtConsumer.accept(String, int) 签名
        stream.thoughtConsumer.accept("推理片段", 1);
        assertEquals("推理片段", capturedThought[0], "ThoughtConsumer 应接收 thought 参数");
        assertEquals(1, capturedIteration[0], "ThoughtConsumer 应接收 iteration 参数");
    }

    /**
     * 验证标准：onAction(ActionConsumer) 方法存在且返回 this（链式）；
     * ActionConsumer.accept(String toolName, String arguments, int iteration) 签名正确
     */
    @Test
    void onActionShouldBeChainableAndCallbackReceivesToolNameArgumentsAndIteration() {
        TestThinkingTokenStream stream = new TestThinkingTokenStream();

        String[] capturedToolName = {null};
        String[] capturedArguments = {null};
        int[] capturedIteration = {-1};

        ThinkingTokenStream returned = stream.onAction((toolName, arguments, iteration) -> {
            capturedToolName[0] = toolName;
            capturedArguments[0] = arguments;
            capturedIteration[0] = iteration;
        });

        assertSame(stream, returned, "onAction 应返回 this 以支持链式调用");

        stream.actionConsumer.accept("search", "{\"query\":\"天气\"}", 2);
        assertEquals("search", capturedToolName[0], "ActionConsumer 应接收 toolName 参数");
        assertEquals("{\"query\":\"天气\"}", capturedArguments[0], "ActionConsumer 应接收 arguments 参数");
        assertEquals(2, capturedIteration[0], "ActionConsumer 应接收 iteration 参数");
    }

    /**
     * 验证标准：onObservation(ObservationConsumer) 方法存在且返回 this（链式）；
     * ObservationConsumer.accept(String result, int iteration) 签名正确
     */
    @Test
    void onObservationShouldBeChainableAndCallbackReceivesResultAndIteration() {
        TestThinkingTokenStream stream = new TestThinkingTokenStream();

        String[] capturedResult = {null};
        int[] capturedIteration = {-1};

        ThinkingTokenStream returned = stream.onObservation((result, iteration) -> {
            capturedResult[0] = result;
            capturedIteration[0] = iteration;
        });

        assertSame(stream, returned, "onObservation 应返回 this 以支持链式调用");

        stream.observationConsumer.accept("工具执行结果", 2);
        assertEquals("工具执行结果", capturedResult[0], "ObservationConsumer 应接收 result 参数");
        assertEquals(2, capturedIteration[0], "ObservationConsumer 应接收 iteration 参数");
    }

    /**
     * 验证标准：onFinalAnswer(FinalAnswerConsumer) 方法存在且返回 this（链式）；
     * FinalAnswerConsumer.accept(int iteration) 签名正确
     */
    @Test
    void onFinalAnswerShouldBeChainableAndCallbackReceivesIteration() {
        TestThinkingTokenStream stream = new TestThinkingTokenStream();

        int[] capturedIteration = {-1};

        ThinkingTokenStream returned = stream.onFinalAnswer(iteration -> {
            capturedIteration[0] = iteration;
        });

        assertSame(stream, returned, "onFinalAnswer 应返回 this 以支持链式调用");

        stream.finalAnswerConsumer.accept(3);
        assertEquals(3, capturedIteration[0], "FinalAnswerConsumer 应接收 iteration 参数");
    }
}
