package com.agentdemo.app.registry;

import com.agentdemo.app.core.OrchestrationMode;
import com.agentdemo.app.core.WorkflowTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工作流模板注册中心
 * <p>
 * 业务含义：集中管理所有已注册的工作流模板，提供注册、查询、列表功能。
 * 使用 ConcurrentHashMap 保证并发安全（AC-020 并发执行多个工作流）。
 * 模板在 Spring 启动时通过 @Bean 方法自动注册（AC-024）。
 * </p>
 */
@Component
public class WorkflowTemplateRegistry {

    private final ConcurrentHashMap<String, WorkflowTemplate> templates = new ConcurrentHashMap<>();

    /**
     * 注册工作流模板
     *
     * @param template 模板对象
     * @throws IllegalStateException 模板 ID 已存在时抛出
     */
    public void register(WorkflowTemplate template) {
        if (templates.containsKey(template.getId())) {
            throw new IllegalStateException("工作流模板 ID 已存在: " + template.getId());
        }
        templates.put(template.getId(), template);
    }

    /**
     * 获取模板
     *
     * @param templateId 模板 ID
     * @return 模板对象
     * @throws com.agentdemo.common.exception.BusinessException 模板不存在时抛出 WORKFLOW_NOT_FOUND
     */
    public WorkflowTemplate getTemplate(String templateId) {
        WorkflowTemplate template = templates.get(templateId);
        if (template == null) {
            throw new com.agentdemo.common.exception.BusinessException(
                    com.agentdemo.common.exception.ErrorCode.WORKFLOW_NOT_FOUND, templateId);
        }
        return template;
    }

    /**
     * 获取所有模板（不可变列表）
     *
     * @return 模板列表
     */
    public List<WorkflowTemplate> listTemplates() {
        return List.copyOf(templates.values());
    }
}
