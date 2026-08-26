package com.agentdemo.app.adapter;

import com.agentdemo.app.core.AgentDefinition;
import com.agentdemo.agent.prompt.PromptTemplateLoader;
import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.llm.registry.ModelFactory;
import com.agentdemo.tools.registry.ToolRegistry;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Agentic Agent 构建工厂
 * <p>
 * 业务含义：将 AgentDefinition 转换为可执行的 Agentic Agent 代理实例，
 * 桥接 ModelFactory（获取流式模型）、ToolRegistry（解析工具）、
 * PromptTemplateLoader（组合系统提示词）。对应 AC-018/AC-019/AC-025/AC-028。
 * </p>
 * <p>
 * 设计要点：
 * 1. 工具在 build 时固化（与 SimpleAgent 一致，AC-025），工具变更需重建 Agent
 * 2. systemMessageProvider 动态提供系统提示词（角色 × 场景模板组合）
 * 3. 返回 Object 类型，由调用方按需转型为具体 Agent 接口
 * </p>
 */
@Component
public class AgenticAgentFactory {

    private static final Logger log = LoggerFactory.getLogger(AgenticAgentFactory.class);

    private final ModelFactory modelFactory;
    private final ToolRegistry toolRegistry;
    private final PromptTemplateLoader promptTemplateLoader;

    public AgenticAgentFactory(ModelFactory modelFactory,
                               ToolRegistry toolRegistry,
                               PromptTemplateLoader promptTemplateLoader) {
        this.modelFactory = modelFactory;
        this.toolRegistry = toolRegistry;
        this.promptTemplateLoader = promptTemplateLoader;
    }

    /**
     * 构建 Agentic Agent 代理
     * <p>
     * 业务含义：根据 Agent 定义（模型、工具、提示词配置）构建可执行的 Agent 代理。
     * 模型未配置或不存在时抛出 WORKFLOW_MODEL_NOT_FOUND（AC-018）。
     * </p>
     *
     * @param agentDef Agent 定义
     * @return Agent 代理实例（接口类型由 agentDef.interfaceClass 指定）
     */
    public Object buildAgent(AgentDefinition agentDef) {
        // 1. 获取流式模型（按 modelId 选择，AC-028；null 使用默认模型）
        StreamingChatModel streamingModel;
        try {
            streamingModel = (agentDef.getModelId() != null)
                    ? modelFactory.getStreamingChatModelByModelId(agentDef.getModelId())
                    : modelFactory.getDefaultStreamingChatModel();
        } catch (BusinessException e) {
            // 模型不存在或未配置时统一转换为工作流错误码（AC-018）
            throw new BusinessException(ErrorCode.WORKFLOW_MODEL_NOT_FOUND,
                    "Agent [" + agentDef.getName() + "] 配置的模型不存在: " + agentDef.getModelId(), e);
        }

        // 2. 解析工具（toolIds 为空时不调用 ToolRegistry，AC-025 工具预定义）
        // 业务含义：工作流非 HITL 路径无暂停确认能力，按 ForDirect 过滤（deny+ask 剔除，
        // 修复非 HITL 双重绕过与 ask 卡死/直执行，AC-S01/AC-E01）
        List<Object> tools = (agentDef.getToolIds() == null || agentDef.getToolIds().isEmpty())
                ? List.of()
                : toolRegistry.resolveToolsForDirect(agentDef.getToolIds());

        // 3. 通过 AgenticServices.agentBuilder 构建 Agent
        // 系统提示词由 PromptTemplateLoader 组合角色模板 + 场景模板动态提供
        log.info("构建 Agentic Agent: name={}, modelId={}, 工具数={}",
                agentDef.getName(), agentDef.getModelId(), tools.size());

        return AgenticServices.agentBuilder(agentDef.getInterfaceClass())
                .streamingChatModel(streamingModel)
                .tools(tools.toArray())
                .systemMessageProvider(memoryId ->
                        promptTemplateLoader.composeSystemPrompt(
                                agentDef.getRoleName(),
                                agentDef.getScenarioName()))
                .build();
    }
}
