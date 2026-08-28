package com.agentdemo.web.controller;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.agent.core.HumanInteractionManager;
import com.agentdemo.agent.core.UnifiedChatStream;
import com.agentdemo.agent.single.PlanAgent;
import com.agentdemo.agent.single.SimpleAgent;
import com.agentdemo.memory.session.SessionManager;
import com.agentdemo.memory.shortterm.ChatMemoryManager;
import com.agentdemo.skill.config.SkillProperties;
import com.agentdemo.skill.entity.SkillDefinition;
import com.agentdemo.skill.session.SkillSessionManager;
import com.agentdemo.skill.store.SkillStore;
import com.agentdemo.tools.permission.ToolPermissionService;
import com.agentdemo.tools.registry.ToolRegistry;
import com.agentdemo.web.handler.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AgentController 技能字段测试（技术方案 Task-18，AC-N03/S04）
 * <p>
 * 业务含义：ChatRequest.skills/excludedSkills 手动指定 → applyManualSelection + skill_activated(manual)
 * SSE 事件；技能不存在返回 400。
 * </p>
 */
class AgentControllerSkillRequestTest {

    private MockMvc mockMvc;
    private SkillStore skillStore;
    private SkillSessionManager skillSessionManager;
    private UnifiedChatStream unifiedStream;
    private SessionManager sessionManager;

    @BeforeEach
    void setUp() {
        SkillProperties properties = new SkillProperties();
        properties.setStorageDir(Path.of(System.getProperty("java.io.tmpdir"), "skill-agentctl-" + System.nanoTime()).toString());
        skillStore = new SkillStore(properties);
        skillSessionManager = new SkillSessionManager(skillStore, properties);

        SkillDefinition s1 = new SkillDefinition();
        s1.setId("s1");
        s1.setName("周报撰写专家");
        s1.setDescription("周报技能");
        s1.setInstruction("按结构写周报");
        skillStore.create(s1);

        SimpleAgent simpleAgent = mock(SimpleAgent.class);
        PlanAgent planAgent = mock(PlanAgent.class);
        // RETURNS_SELF：链式回调注册（onPartialResponse().onComplete()...）返回自身，避免 null NPE
        unifiedStream = mock(UnifiedChatStream.class, org.mockito.Answers.RETURNS_SELF);
        when(planAgent.chatUnifiedStream(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(unifiedStream);
        ChatMemoryManager memoryManager = mock(ChatMemoryManager.class);

        sessionManager = new SessionManager();
        AgentController controller = new AgentController(
                simpleAgent, planAgent, sessionManager, memoryManager,
                mock(ToolRegistry.class), new AgentConfig(), new HumanInteractionManager(),
                mock(ToolPermissionService.class), skillSessionManager);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    void manualSkillsShouldActivateSessionState() throws Exception {
        String sessionId = sessionManager.createSession();
        mockMvc.perform(post("/api/agent/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"" + sessionId + "\",\"message\":\"写周报\",\"skills\":[\"s1\"]}"))
                .andExpect(status().isOk());

        // 手动指定已生效（会话激活态含 s1 + 手动模式）
        assertThat(skillSessionManager.getActiveSkillIds(sessionId)).containsExactly("s1");
        assertThat(skillSessionManager.isManualMode(sessionId)).isTrue();
    }

    @Test
    void excludedSkillsShouldBeRecorded() throws Exception {
        String sessionId = sessionManager.createSession();
        mockMvc.perform(post("/api/agent/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"" + sessionId + "\",\"message\":\"写周报\",\"excludedSkills\":[\"s1\"]}"))
                .andExpect(status().isOk());

        assertThat(skillSessionManager.getExcludedSkillIds(sessionId)).containsExactly("s1");
    }

    @Test
    void nonexistentSkillShouldReturnError() throws Exception {
        String sessionId = sessionManager.createSession();
        mockMvc.perform(post("/api/agent/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"" + sessionId + "\",\"message\":\"写周报\",\"skills\":[\"ghost\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));
    }
}
