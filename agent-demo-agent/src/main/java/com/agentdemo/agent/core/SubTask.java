package com.agentdemo.agent.core;

/**
 * 子任务数据模型（CR-002 新增）
 * <p>
 * 业务含义：任务拆解规划阶段产出的单个子任务定义。
 * 作为不可变值对象，通过 record 自动生成构造器、访问器、equals/hashCode/toString。
 * </p>
 * <p>
 * 关联 AC：AC-016（子任务数据结构完整性）
 * </p>
 *
 * @param index 序号，1-based（与前端展示一致）
 * @param title 子任务标题描述
 */
public record SubTask(int index, String title) {
}
