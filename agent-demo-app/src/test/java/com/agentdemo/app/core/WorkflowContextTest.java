package com.agentdemo.app.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WorkflowContext 状态容器测试（P2 Task-01）
 * <p>
 * 业务含义：验证 Agent 间共享状态容器的读写、输出记录、迭代计数与线程安全。
 * </p>
 */
class WorkflowContextTest {

    @Test
    void writeAndRead_shouldReturnSameValue() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.write("topic", "AI Agent 技术趋势");
        assertEquals("AI Agent 技术趋势", ctx.read("topic"));
    }

    @Test
    void readNonExistentKey_shouldReturnNullAndEmptyString() {
        WorkflowContext ctx = new WorkflowContext();
        assertNull(ctx.read("non-existent"));
        assertEquals("", ctx.readAsString("non-existent"));
    }

    @Test
    void readAsString_shouldConvertValueToString() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.write("score", 92);
        assertEquals("92", ctx.readAsString("score"));
    }

    @Test
    void recordOutputAndGetOutput_shouldWorkByAgentName() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.recordOutput("安全审查 Agent", "发现 2 个安全问题");
        assertEquals("发现 2 个安全问题", ctx.getOutput("安全审查 Agent"));
        assertEquals("", ctx.getOutput("non-existent"));
    }

    @Test
    void incrementIteration_shouldCountSequentially() {
        WorkflowContext ctx = new WorkflowContext();
        assertEquals(1, ctx.incrementIteration());
        assertEquals(2, ctx.incrementIteration());
        assertEquals(3, ctx.incrementIteration());
        assertEquals(3, ctx.getIterationCount());
    }

    @Test
    void getState_shouldExposeAllWrittenValues() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.write("a", 1);
        ctx.write("b", "two");
        assertEquals(2, ctx.getState().size());
        assertEquals(1, ctx.getState().get("a"));
        assertEquals("two", ctx.getState().get("b"));
    }

    @Test
    void concurrentWrites_shouldNotThrow() throws InterruptedException {
        WorkflowContext ctx = new WorkflowContext();
        int threadCount = 8;
        int writesPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < writesPerThread; i++) {
                        ctx.write("key-" + threadId + "-" + i, i);
                        ctx.recordOutput("agent-" + threadId, "out-" + i);
                        ctx.incrementIteration();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "并发写入应在超时内完成");
        executor.shutdown();

        // 总写入数 = threadCount * writesPerThread（key 维度）
        assertEquals(threadCount * writesPerThread, ctx.getState().size());
        // 迭代计数 = threadCount * writesPerThread（每次 write 都 increment）
        assertEquals(threadCount * writesPerThread, ctx.getIterationCount());
    }
}
