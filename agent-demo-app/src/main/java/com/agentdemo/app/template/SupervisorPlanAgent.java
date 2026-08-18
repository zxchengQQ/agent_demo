package com.agentdemo.app.template;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Supervisor 主控-拆解 Agent 接口（P3 Task-12，AC-007）
 * <p>
 * 业务含义：输入原始复杂任务，输出子任务 JSON 数组（由场景提示词
 * app-supervisor-plan.txt 约束输出格式，SubtaskParser 三层容错解析）。
 * 主控用强模型（BR-APP-012，拆解质量决定编排质量）。
 * </p>
 */
public interface SupervisorPlanAgent {

    @Agent
    @UserMessage("{{task}}")
    TokenStream execute(@V("task") String task);
}
