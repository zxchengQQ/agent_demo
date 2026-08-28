package com.agentdemo.agent.single;

import com.agentdemo.agent.config.AgentConfig;
import com.agentdemo.skill.config.SkillProperties;
import com.agentdemo.skill.entity.ScriptParam;
import com.agentdemo.skill.entity.SkillDefinition;
import com.agentdemo.skill.entity.SkillScript;
import com.agentdemo.skill.script.SkillScriptExecutor;
import com.agentdemo.skill.script.SkillScriptToolRegistrar;
import com.agentdemo.skill.session.SkillSessionManager;
import com.agentdemo.skill.store.SkillStore;
import com.agentdemo.skill.tool.SkillLoadTool;
import com.agentdemo.tools.registry.ToolRegistry;
import com.agentdemo.tools.registry.ToolSchemaConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SkillToolInterceptorImpl 测试（技术方案 Task-13 配套，AC-N01/T02；CR-001 适配脚本工具）
 * <p>
 * 业务含义：验证流式路径 loadSkill 拦截的完整协作——激活成功生成事件载荷（自带脚本工具名）
 * + 热刷新 toolsJson；激活失败不触发事件与刷新。
 * </p>
 */
class SkillToolInterceptorImplTest {

    private SkillStore store;
    private SkillSessionManager sessionManager;
    private SkillLoadTool skillLoadTool;
    private SessionToolResolver sessionToolResolver;
    private ToolSchemaConverter converter;
    private SkillToolInterceptorImpl interceptor;
    private AgentConfig agentConfig;

    @BeforeEach
    void setUp() {
        SkillProperties properties = new SkillProperties();
        properties.setStorageDir(Path.of(System.getProperty("java.io.tmpdir"), "skill-interceptor-" + System.nanoTime()).toString());
        store = new SkillStore(properties);
        sessionManager = new SkillSessionManager(store, properties);
        skillLoadTool = new SkillLoadTool(store, sessionManager, properties);

        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        agentConfig = new AgentConfig();
        agentConfig.setEnableLogging(false);
        agentConfig.getTools().setDefaultTools(List.of());

        SkillScriptToolRegistrar registrar = new SkillScriptToolRegistrar(toolRegistry, new SkillScriptExecutor());
        sessionToolResolver = new SessionToolResolver(toolRegistry, agentConfig, sessionManager, registrar);
        converter = mock(ToolSchemaConverter.class);
        when(converter.convertToJson(anyList())).thenReturn("[script-tool-schema]");

        interceptor = new SkillToolInterceptorImpl(skillLoadTool, sessionManager, sessionToolResolver, converter);

        SkillDefinition s1 = new SkillDefinition();
        s1.setId("s1");
        s1.setName("数据查询助手");
        s1.setDescription("查询数据");
        s1.setInstruction("优先使用自带脚本工具查询数据");
        s1.setScripts(List.of(new SkillScript("http-get", "shell", "HTTP 获取公开数据",
                List.of(new ScriptParam("url", "string", true, "URL")),
                "curl -s \"$SKILL_PARAM_URL\"")));
        store.create(s1);
    }

    @Test
    void shouldActivateAndProduceEventPayloadAndRefreshToolsJson() {
        var result = interceptor.interceptLoadSkill("sess", "s1", "[old-tools]", 1);

        assertThat(result.activated()).isTrue();
        assertThat(result.skillId()).isEqualTo("s1");
        assertThat(result.skillName()).isEqualTo("数据查询助手");
        assertThat(result.source()).isEqualTo("AUTO");
        // 事件载荷 = 自带脚本工具名（skill_{skillId}_{scriptName}，CR-001）
        assertThat(result.boundToolIds()).containsExactly("skill_s1_http_get");
        // 热刷新返回新 toolsJson（含脚本工具）
        assertThat(result.refreshedToolsJson()).isEqualTo("[script-tool-schema]");
        // 观察值含技能名称
        assertThat(result.observation()).contains("数据查询助手");
        // 激活态已写入
        assertThat(sessionManager.getActiveSkillIds("sess")).containsExactly("s1");
    }

    @Test
    void shouldNotActivateWhenSkillNotFound() {
        var result = interceptor.interceptLoadSkill("sess", "ghost", "[old-tools]", 1);
        assertThat(result.activated()).isFalse();
        assertThat(result.refreshedToolsJson()).isNull();
        assertThat(result.observation()).contains("不存在");
        assertThat(sessionManager.getActiveSkillIds("sess")).isEmpty();
    }
}
