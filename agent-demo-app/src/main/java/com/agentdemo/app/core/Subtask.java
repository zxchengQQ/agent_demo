package com.agentdemo.app.core;

import lombok.Builder;
import lombok.Data;

/**
 * 子任务模型（P3 新增，AC-007）
 * <p>
 * 业务含义：主控拆解输出解析后的内存表示——description 是 Worker 的输入，
 * agent 是主控指定的 Worker 名（路由 key，三级路由匹配）。
 * </p>
 */
@Data
@Builder
public class Subtask {

    /** 子任务编号（1..N，前端卡片标识） */
    private int id;

    /** 子任务描述（Worker 的输入） */
    private String description;

    /** 主控指定的 Worker 名（路由 key） */
    private String agent;
}
