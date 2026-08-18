package com.agentdemo.app.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工作流执行上下文
 * <p>
 * 业务含义：Agent 间数据传递的共享容器，替代 P1 的单字符串 currentInput。
 * 支持命名状态读写（state）、按 Agent 名记录输出（agentOutputs）、迭代计数（iterationCount）。
 * 并行/循环模式的多 Agent 通过此容器交换数据（AC-004/AC-006/AC-029）。
 * </p>
 * <p>
 * 线程安全：内部使用 ConcurrentHashMap + AtomicInteger，支持并行模式下多分组并发读写。
 * </p>
 */
public class WorkflowContext {

    /** 命名状态存储 */
    private final Map<String, Object> state = new ConcurrentHashMap<>();

    /** 按 Agent 名记录输出 */
    private final Map<String, String> agentOutputs = new ConcurrentHashMap<>();

    /** 迭代计数（循环模式每轮递增） */
    private final AtomicInteger iterationCount = new AtomicInteger(0);

    /**
     * 写入命名状态
     *
     * @param key   状态键
     * @param value 状态值
     */
    public void write(String key, Object value) {
        state.put(key, value);
    }

    /**
     * 读取命名状态
     *
     * @param key 状态键
     * @return 状态值（不存在返回 null）
     */
    public Object read(String key) {
        return state.get(key);
    }

    /**
     * 读取命名状态并转为字符串
     *
     * @param key 状态键
     * @return 状态值字符串（不存在返回空字符串）
     */
    public String readAsString(String key) {
        Object value = state.get(key);
        return value != null ? value.toString() : "";
    }

    /**
     * 记录某个 Agent 的输出
     *
     * @param agentName Agent 名称
     * @param output    输出文本
     */
    public void recordOutput(String agentName, String output) {
        agentOutputs.put(agentName, output);
    }

    /**
     * 获取某个 Agent 的输出
     *
     * @param agentName Agent 名称
     * @return 输出文本（不存在返回空字符串）
     */
    public String getOutput(String agentName) {
        return agentOutputs.getOrDefault(agentName, "");
    }

    /**
     * 迭代计数自增并返回新值（循环模式每轮调用）
     *
     * @return 自增后的迭代次数
     */
    public int incrementIteration() {
        return iterationCount.incrementAndGet();
    }

    /**
     * 获取当前迭代次数
     *
     * @return 迭代次数
     */
    public int getIterationCount() {
        return iterationCount.get();
    }

    /**
     * 获取全部命名状态（供汇总/测试使用）
     *
     * @return 状态 Map
     */
    public Map<String, Object> getState() {
        return state;
    }
}
