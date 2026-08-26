package com.agentdemo.web.controller;

import com.agentdemo.app.registry.WorkflowTemplateRegistry;
import com.agentdemo.app.service.WorkflowExecutionService;
import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.web.dto.HITLReplyRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * WorkflowController hitl-reply 端点测试（Task-10）
 * <p>
 * 验证标准来源：Task-10 验证标准
 * 关联 AC：AC-N03（用户回复后恢复执行）、AC-S01（检查点拒绝后终止）
 * </p>
 * <p>
 * 业务含义：前端通过 POST /executions/{executionId}/hitl-reply 回复工作流中 Agent 的提问
 * （askUser 模式携带 message）或确认检查点（checkpoint 模式携带 approved）。端点返回
 * SseEmitter（timeout=0L 与 execute/resume 对齐），委托 WorkflowExecutionService.hitlReply
 * 完成状态校验与恢复；message/approved 至少一个非 null（空请求抛 WORKFLOW_PARAM_MISSING）。
 * </p>
 */
class WorkflowControllerHITLTest {

    private WorkflowExecutionService executionService;
    private WorkflowController controller;

    @BeforeEach
    void setUp() {
        WorkflowTemplateRegistry registry = mock(WorkflowTemplateRegistry.class);
        executionService = mock(WorkflowExecutionService.class);
        controller = new WorkflowController(registry, executionService);
    }

    // ========== 验证标准：HITLReplyRequest DTO 包含 message（可选）和 approved（可选）字段 ==========

    @Test
    void requestDto_应含message与approved字段() {
        HITLReplyRequest request = new HITLReplyRequest();
        request.setMessage("用户回复");
        request.setApproved(true);
        assertEquals("用户回复", request.getMessage());
        assertEquals(Boolean.TRUE, request.getApproved());
    }

    // ========== 验证标准：POST /hitl-reply 返回 SseEmitter（timeout=0L，与 execute/resume 一致）==========

    @Test
    void hitlReply_askUser_应返回SseEmitter并委托service() {
        HITLReplyRequest request = new HITLReplyRequest();
        request.setMessage("用户回复");

        SseEmitter emitter = controller.hitlReply("exec-1", request);
        assertNotNull(emitter, "hitl-reply 应返回 SseEmitter");
        // askUser 模式：message 透传，approved 为 null
        verify(executionService).hitlReply(eq("exec-1"), eq("用户回复"), isNull(), any(SseEmitter.class));
    }

    @Test
    void hitlReply_checkpoint_应透传approved() {
        HITLReplyRequest request = new HITLReplyRequest();
        request.setApproved(false);

        controller.hitlReply("exec-1", request);
        // checkpoint 拒绝：approved=false 透传，message 为 null
        verify(executionService).hitlReply(eq("exec-1"), isNull(), eq(false), any(SseEmitter.class));
    }

    @Test
    void hitlReply_应使用永不超时emitter() throws Exception {
        HITLReplyRequest request = new HITLReplyRequest();
        request.setMessage("回复");

        SseEmitter emitter = controller.hitlReply("exec-1", request);
        assertEquals(0L, readEmitterTimeout(emitter), "hitl-reply SSE emitter 应配置为永不超时（0L）");
    }

    // ========== 验证标准：参数校验——message 和 approved 至少一个非 null ==========

    @Test
    void hitlReply_空请求_应抛ParamMissing() {
        HITLReplyRequest request = new HITLReplyRequest(); // 两者均为 null

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.hitlReply("exec-1", request));
        assertEquals(ErrorCode.WORKFLOW_PARAM_MISSING, ex.getErrorCode());
    }

    /**
     * 反射读取 SseEmitter 的超时值（ResponseBodyEmitter.timeout 私有字段，无公开 getter）
     */
    private static long readEmitterTimeout(SseEmitter emitter) throws Exception {
        java.lang.reflect.Field field = org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.class
                .getDeclaredField("timeout");
        field.setAccessible(true);
        Long timeout = (Long) field.get(emitter);
        return timeout == null ? -1L : timeout;
    }
}
