package com.agentdemo.app.registry;

import com.agentdemo.app.core.OrchestrationMode;
import com.agentdemo.app.core.WorkflowTemplate;
import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模板注册中心测试（Task-05）
 */
class WorkflowTemplateRegistryTest {

    private WorkflowTemplateRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new WorkflowTemplateRegistry();
    }

    private WorkflowTemplate template(String id) {
        return WorkflowTemplate.builder()
                .id(id)
                .name("模板-" + id)
                .mode(OrchestrationMode.SEQUENTIAL)
                .agents(List.of())
                .parameters(List.of())
                .build();
    }

    @Test
    void register_shouldMakeTemplateListed() {
        registry.register(template("test-id"));
        List<WorkflowTemplate> templates = registry.listTemplates();
        assertEquals(1, templates.size());
        assertEquals("test-id", templates.get(0).getId());
    }

    @Test
    void getTemplate_shouldReturnRegisteredTemplate() {
        registry.register(template("test-id"));
        WorkflowTemplate found = registry.getTemplate("test-id");
        assertNotNull(found);
        assertEquals("test-id", found.getId());
    }

    @Test
    void getTemplate_shouldThrowWhenNotExist() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> registry.getTemplate("non-existent"));
        assertEquals(ErrorCode.WORKFLOW_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void register_shouldThrowWhenDuplicateId() {
        registry.register(template("test-id"));
        assertThrows(IllegalStateException.class, () -> registry.register(template("test-id")));
    }

    @Test
    void listTemplates_shouldReturnImmutableList() {
        registry.register(template("test-id"));
        List<WorkflowTemplate> templates = registry.listTemplates();
        assertThrows(UnsupportedOperationException.class, () -> templates.add(template("another")));
    }

    @Test
    void listTemplates_shouldReturnAllRegistered() {
        registry.register(template("t1"));
        registry.register(template("t2"));
        assertTrue(registry.listTemplates().size() == 2);
    }
}
