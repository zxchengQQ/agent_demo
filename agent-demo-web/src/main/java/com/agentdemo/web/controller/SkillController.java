package com.agentdemo.web.controller;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.common.result.Result;
import com.agentdemo.skill.entity.SkillDefinition;
import com.agentdemo.skill.entity.SkillResource;
import com.agentdemo.skill.entity.SkillSource;
import com.agentdemo.skill.security.SkillContentValidator;
import com.agentdemo.skill.store.SkillStore;
import com.agentdemo.web.dto.SkillRequest;
import com.agentdemo.web.dto.SkillResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 技能管理 REST API
 * <p>
 * 业务含义：技能管理页的后端接口（技术方案 Task-17，AC-N06/S01/H03）。
 * 对话通道不可达（AC-E03 结构隔离），配置仅经本管理 API 变更。
 * </p>
 * <p>
 * 校验：内容安全（SkillContentValidator 恶意指令阻断/密钥警告/Token 上限）+ 绑定工具存在性
 * （ToolRegistry 解析）+ Bean Validation。
 * </p>
 */
@Tag(name = "技能管理", description = "技能 CRUD 与启停管理接口")
@RestController
@RequestMapping("/api/skill")
public class SkillController {

    private static final Logger log = LoggerFactory.getLogger(SkillController.class);

    private final SkillStore skillStore;
    private final SkillContentValidator contentValidator;

    public SkillController(SkillStore skillStore,
                           SkillContentValidator contentValidator) {
        this.skillStore = skillStore;
        this.contentValidator = contentValidator;
    }

    /**
     * 查询技能列表
     */
    @Operation(summary = "查询技能列表", description = "返回全部技能（含启用状态与绑定工具）")
    @GetMapping("/list")
    public Result<List<SkillResponse>> list() {
        List<SkillResponse> responses = skillStore.list().stream()
                .map(SkillResponse::from)
                .toList();
        return Result.success(responses);
    }

    /**
     * 创建技能（内容安全校验 + 绑定工具校验）
     */
    @Operation(summary = "创建技能", description = "创建新技能；恶意指令内容被拦截并返回原因（AC-S01）")
    @PostMapping
    public Result<SkillResponse> create(@Valid @RequestBody SkillRequest request) {
        SkillDefinition skill = toEntity(request);
        List<String> warnings = validateContent(skill, "创建");

        SkillDefinition created;
        try {
            created = skillStore.create(skill);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, e.getMessage());
        }
        SkillResponse response = SkillResponse.from(created);
        response.setWarnings(warnings);
        log.info("技能创建: id={}", created.getId());
        return Result.success(response);
    }

    /**
     * 更新技能（内容安全校验 + 绑定工具校验）
     */
    @Operation(summary = "更新技能", description = "更新技能定义；恶意指令内容被拦截并返回原因（AC-S01）")
    @PutMapping("/{skillId}")
    public Result<SkillResponse> update(@PathVariable String skillId,
                                        @Valid @RequestBody SkillRequest request) {
        SkillDefinition skill = toEntity(request);
        skill.setId(skillId);
        skill.setSource(resolveExistingSource(skillId));
        List<String> warnings = validateContent(skill, "更新");

        SkillDefinition updated = skillStore.update(skill);
        SkillResponse response = SkillResponse.from(updated);
        response.setWarnings(warnings);
        log.info("技能更新: id={}", skillId);
        return Result.success(response);
    }

    /**
     * 启用/禁用技能
     */
    @Operation(summary = "启用/禁用技能", description = "切换技能启用状态（禁用后不可激活、目录段剔除，AC-E04）")
    @PutMapping("/{skillId}/enabled")
    public Result<Void> setEnabled(@PathVariable String skillId,
                                   @RequestBody EnableRequest request) {
        SkillDefinition skill = skillStore.get(skillId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "技能不存在: " + skillId));
        skill.setEnabled(request.isEnabled());
        skillStore.update(skill);
        log.info("技能启停: id={}, enabled={}", skillId, request.isEnabled());
        return Result.success();
    }

    /**
     * 删除技能
     */
    @Operation(summary = "删除技能", description = "删除技能定义及其文件；已激活会话下一轮平滑退出（AC-E04）")
    @DeleteMapping("/{skillId}")
    public Result<Void> delete(@PathVariable String skillId) {
        skillStore.delete(skillId);
        log.info("技能删除: id={}", skillId);
        return Result.success();
    }

    // ==================== 内部 ====================

    /**
     * 内容安全校验：恶意指令阻断（抛 PARAM_INVALID 带命中类别，AC-H03）；
     * 密钥警告返回供响应透传（AC-S01 警告不阻断语义）。
     *
     * @return 校验警告列表（密钥类，可空）
     */
    private List<String> validateContent(SkillDefinition skill, String action) {
        SkillContentValidator.Result result = contentValidator.validate(skill);
        if (result.blocked()) {
            log.warn("技能内容校验拦截（{}）: id={}, 原因={}", action, skill.getId(), result.blockReason());
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "技能内容不合规: " + result.blockReason());
        }
        return result.warnings() == null ? List.of() : result.warnings();
    }

    private SkillDefinition toEntity(SkillRequest request) {
        SkillDefinition skill = new SkillDefinition();
        skill.setId(request.getId());
        skill.setName(request.getName());
        skill.setDescription(request.getDescription());
        skill.setInstruction(request.getInstruction());
        if (request.getResources() != null) {
            List<SkillResource> resources = new ArrayList<>();
            for (SkillRequest.ResourceItem item : request.getResources()) {
                if (item == null) {
                    continue;
                }
                resources.add(new SkillResource(item.getName(), item.getContent()));
            }
            skill.setResources(resources);
        }
        // CR-001：绑定系统工具 → 自带脚本工具
        skill.setScripts(toScriptEntities(request.getScripts()));
        // 新创建默认自定义来源；编辑时保留既有来源
        skill.setSource(SkillSource.CUSTOM);
        return skill;
    }

    /**
     * 请求脚本项 → 领域脚本实体（CR-001）
     */
    private List<com.agentdemo.skill.entity.SkillScript> toScriptEntities(List<SkillRequest.ScriptItem> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        List<com.agentdemo.skill.entity.SkillScript> scripts = new ArrayList<>();
        for (SkillRequest.ScriptItem item : items) {
            if (item == null) {
                continue;
            }
            com.agentdemo.skill.entity.SkillScript script = new com.agentdemo.skill.entity.SkillScript();
            script.setName(item.getName());
            script.setLanguage(item.getLanguage());
            script.setDescription(item.getDescription());
            script.setContent(item.getContent());
            if (item.getParams() != null) {
                List<com.agentdemo.skill.entity.ScriptParam> params = new ArrayList<>();
                for (SkillRequest.ParamItem p : item.getParams()) {
                    if (p == null) {
                        continue;
                    }
                    params.add(new com.agentdemo.skill.entity.ScriptParam(
                            p.getName(), p.getType(), p.isRequired(), p.getDescription()));
                }
                script.setParams(params);
            }
            scripts.add(script);
        }
        return scripts;
    }

    private SkillSource resolveExistingSource(String skillId) {
        return skillStore.get(skillId)
                .map(SkillDefinition::getSource)
                .orElse(SkillSource.CUSTOM);
    }

    /**
     * 启停请求体
     */
    public static class EnableRequest {
        private boolean enabled;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
