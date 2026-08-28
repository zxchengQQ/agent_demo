package com.agentdemo.skill.store;

import com.agentdemo.skill.config.SkillProperties;
import com.agentdemo.skill.entity.ScriptParam;
import com.agentdemo.skill.entity.SkillDefinition;
import com.agentdemo.skill.entity.SkillResource;
import com.agentdemo.skill.entity.SkillScript;
import com.agentdemo.skill.entity.SkillSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 技能定义存储（标准目录结构，CR-001 Task-30）
 * <p>
 * 业务含义：技能定义的 CRUD 与持久化（技术方案 skill-store，AC-N08）。每技能一个标准目录
 * （data/skills/{id}/），含 SKILL.md（YAML front-matter 元数据 + Markdown 指令正文）、
 * scripts/（自带脚本内容）、reference/（只读参考资源内容）。内存 Map 缓存 + 变更同步落盘。
 * </p>
 * <p>
 * 目录结构（Anthropic Agent Skills 风格）：
 * <pre>
 * data/skills/{id}/
 *   SKILL.md            # front-matter: id/name/description/enabled/source/scripts声明/resources声明 + 正文指令
 *   scripts/{name}.{ext}  # 脚本内容
 *   reference/{name}    # 资源内容
 * </pre>
 * </p>
 * <p>
 * 迁移：启动时自动将旧版单文件 JSON（data/skills/{id}.json）迁移为目录结构，源文件备份为 .bak；
 * 幂等（已迁移目录跳过，不覆盖用户编辑）；单文件损坏跳过（WARN，不阻断启动）。
 * </p>
 * <p>
 * 持久化降级：文件不存在→空配置；单目录损坏→跳过（WARN）；写失败→WARN 内存态仍生效。
 * </p>
 */
@Component
public class SkillStore {

    private static final Logger log = LoggerFactory.getLogger(SkillStore.class);

    private static final String JSON_EXT = ".json";
    private static final String SKILL_MD = "SKILL.md";
    private static final String SCRIPTS_DIR = "scripts";
    private static final String REFERENCE_DIR = "reference";
    private static final String FRONT_MATTER_DELIMITER = "---";

    private final SkillProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
    private final Path storageDir;
    private final ConcurrentMap<String, SkillDefinition> skills = new ConcurrentHashMap<>();

    /** 文件写锁（技能 CRUD 变更频率低，同步写 + 对象锁足够，不过度设计） */
    private final Object fileLock = new Object();

    public SkillStore(SkillProperties properties) {
        this.properties = properties;
        this.storageDir = Path.of(properties.getStorageDir()).toAbsolutePath().normalize();
        loadFromDisk();
    }

    /**
     * 创建技能（id 唯一）
     *
     * @param skill 技能定义（id 必填）
     * @return 创建后的技能
     * @throws IllegalArgumentException id 已存在
     */
    public SkillDefinition create(SkillDefinition skill) {
        requireId(skill);
        String id = skill.getId();
        SkillDefinition existing = skills.putIfAbsent(id, skill);
        if (existing != null) {
            throw new IllegalArgumentException("技能已存在: " + id);
        }
        persist(skill);
        log.info("技能创建: id={}, name={}", id, skill.getName());
        return skill;
    }

    /**
     * 更新技能（不存在则创建）
     *
     * @param skill 技能定义（id 必填）
     * @return 更新后的技能
     */
    public SkillDefinition update(SkillDefinition skill) {
        requireId(skill);
        skills.put(skill.getId(), skill);
        persist(skill);
        log.info("技能更新: id={}, name={}", skill.getId(), skill.getName());
        return skill;
    }

    /**
     * 删除技能（删除整个目录）
     *
     * @param skillId 技能 id
     */
    public void delete(String skillId) {
        if (skillId == null) {
            return;
        }
        SkillDefinition removed = skills.remove(skillId);
        if (removed != null) {
            deleteDir(skillId);
            log.info("技能删除: id={}", skillId);
        }
    }

    /**
     * 查询全部技能（按 id 排序，输出稳定）
     */
    public List<SkillDefinition> list() {
        return skills.values().stream()
                .sorted(Comparator.comparing(SkillDefinition::getId))
                .toList();
    }

    /**
     * 预置技能播种（幂等，兼容目录结构与旧版 JSON 两种 classpath 形态）
     * <p>
     * 业务含义：启动时从 classpath 预置目录播种出厂技能（技术方案 4.3）。幂等语义：
     * 目标技能目录已存在（用户编辑过/已播种）则跳过，不覆盖用户修改（AC-N06 预置初始态）。
     * </p>
     *
     * @param presetsDir classpath 预置技能目录（src/main/resources/skills）
     */
    public void seedPresets(Path presetsDir) {
        if (presetsDir == null || !Files.isDirectory(presetsDir)) {
            log.warn("预置技能目录不存在，跳过播种: {}", presetsDir);
            return;
        }
        try (var stream = Files.list(presetsDir)) {
            List<Path> entries = stream.toList();
            int seeded = 0;
            for (Path entry : entries) {
                // 目录形态：{id}/SKILL.md（标准目录结构）
                if (Files.isDirectory(entry) && Files.exists(entry.resolve(SKILL_MD))) {
                    try {
                        SkillDefinition preset = readSkillMd(entry);
                        if (seedPreset(preset)) {
                            seeded++;
                        }
                    } catch (IOException | RuntimeException e) {
                        log.warn("预置技能加载失败，跳过: {}，原因: {}", entry.getFileName(), e.getMessage());
                    }
                    continue;
                }
                // 旧版 JSON 形态（兼容历史预置资源）
                if (Files.isRegularFile(entry) && entry.getFileName().toString().endsWith(JSON_EXT)) {
                    try {
                        if (seedPreset(entry.getFileName().toString(), Files.readString(entry))) {
                            seeded++;
                        }
                    } catch (IOException e) {
                        log.warn("预置技能加载失败，跳过: {}，原因: {}", entry.getFileName(), e.getMessage());
                    }
                }
            }
            if (seeded > 0) {
                log.info("预置技能播种完成: {} 个（目录: {}）", seeded, presetsDir);
            }
        } catch (IOException e) {
            log.warn("预置技能目录读取失败: {}", presetsDir, e);
        }
    }

    /**
     * 预置技能播种（按 JSON 内容，幂等；兼容 jar 打包场景与旧版 JSON 形态）
     *
     * @param fileName    预置文件名（如 weekly-report-expert.json）
     * @param jsonContent 预置技能 JSON 内容
     * @return 是否实际播种
     */
    public boolean seedPreset(String fileName, String jsonContent) {
        if (fileName == null || jsonContent == null) {
            return false;
        }
        try {
            SkillDefinition preset = objectMapper.readValue(jsonContent, SkillDefinition.class);
            return seedPreset(preset);
        } catch (IOException | RuntimeException e) {
            log.warn("预置技能加载失败，跳过: {}，原因: {}", fileName, e.getMessage());
            return false;
        }
    }

    /**
     * 预置技能播种（按实体，幂等：目标目录已存在则不覆盖）
     *
     * @param preset 预置技能定义
     * @return 是否实际播种
     */
    private boolean seedPreset(SkillDefinition preset) {
        if (preset == null || preset.getId() == null || preset.getId().isBlank()) {
            return false;
        }
        // 幂等：目标目录已存在则不覆盖（用户可能已编辑）
        if (Files.isDirectory(storageDir.resolve(preset.getId()))) {
            return false;
        }
        skills.put(preset.getId(), preset);
        persist(preset);
        return true;
    }

    /**
     * 查询启用技能（供目录段组装与激活判定过滤）
     */
    public List<SkillDefinition> getEnabledSkills() {
        return skills.values().stream()
                .filter(SkillDefinition::isEnabled)
                .sorted(Comparator.comparing(SkillDefinition::getId))
                .toList();
    }

    /**
     * 按 id 查询技能
     */
    public Optional<SkillDefinition> get(String skillId) {
        if (skillId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(skills.get(skillId));
    }

    // ==================== 持久化 ====================

    /**
     * 启动加载：先迁移旧版单文件 JSON，再加载标准目录结构
     */
    private void loadFromDisk() {
        if (!Files.isDirectory(storageDir)) {
            return;
        }
        migrateLegacyJsonFiles();
        loadSkillDirectories();
        log.info("技能存储加载完成: {} 个技能，目录: {}", skills.size(), storageDir);
    }

    /**
     * 迁移旧版单文件 JSON（data/skills/{id}.json）为标准目录结构（AC-N08）
     * <p>
     * 幂等：对应目录已存在则跳过；迁移成功后源文件备份为 {id}.json.bak；损坏文件跳过（WARN）。
     * </p>
     */
    private void migrateLegacyJsonFiles() {
        try (var stream = Files.list(storageDir)) {
            List<Path> jsonFiles = stream
                    .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(JSON_EXT))
                    .toList();
            for (Path file : jsonFiles) {
                String fileName = file.getFileName().toString();
                String id = fileName.substring(0, fileName.length() - JSON_EXT.length());
                if (Files.isDirectory(storageDir.resolve(id))) {
                    continue; // 已迁移，跳过（不覆盖用户编辑）
                }
                try {
                    SkillDefinition skill = objectMapper.readValue(file.toFile(), SkillDefinition.class);
                    if (skill.getId() == null || skill.getId().isBlank()) {
                        throw new IOException("技能文件缺少 id 字段: " + fileName);
                    }
                    skills.put(skill.getId(), skill);
                    persist(skill);
                    Files.move(file, file.resolveSibling(fileName + ".bak"), StandardCopyOption.REPLACE_EXISTING);
                    log.info("旧版技能 JSON 已迁移为目录结构: {} -> {}/", fileName, id);
                } catch (IOException | RuntimeException e) {
                    log.warn("旧版技能 JSON 迁移失败，跳过: {}，原因: {}", fileName, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("技能迁移目录读取失败: {}", storageDir, e);
        }
    }

    /**
     * 加载标准目录结构技能（遍历 {id}/SKILL.md）
     */
    private void loadSkillDirectories() {
        try (var stream = Files.list(storageDir)) {
            List<Path> dirs = stream
                    .filter(p -> Files.isDirectory(p) && Files.exists(p.resolve(SKILL_MD)))
                    .toList();
            for (Path dir : dirs) {
                try {
                    SkillDefinition skill = readSkillMd(dir);
                    skills.put(skill.getId(), skill);
                } catch (IOException | RuntimeException e) {
                    log.warn("技能目录加载失败，跳过: {}，原因: {}", dir.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("技能存储目录读取失败，降级为空: {}", storageDir, e);
        }
    }

    /**
     * 同步写技能目录（SKILL.md + scripts/ + reference/；写失败 WARN 不阻断，内存态仍生效）
     */
    private void persist(SkillDefinition skill) {
        synchronized (fileLock) {
            try {
                Path dir = storageDir.resolve(skill.getId());
                Files.createDirectories(dir.resolve(SCRIPTS_DIR));
                Files.createDirectories(dir.resolve(REFERENCE_DIR));
                writeScriptFiles(skill, dir);
                writeResourceFiles(skill, dir);
                Files.writeString(dir.resolve(SKILL_MD), toSkillMd(skill), StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                log.warn("技能持久化失败（内存态仍生效）: id={}, 原因: {}", skill.getId(), e.getMessage());
            }
        }
    }

    /**
     * 序列化技能为 SKILL.md（YAML front-matter 元数据 + Markdown 指令正文）
     */
    private String toSkillMd(SkillDefinition skill) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id", skill.getId());
        meta.put("name", skill.getName());
        meta.put("description", skill.getDescription());
        meta.put("enabled", skill.isEnabled());
        meta.put("source", skill.getSource() == null ? SkillSource.CUSTOM.name() : skill.getSource().name());

        if (skill.getScripts() != null && !skill.getScripts().isEmpty()) {
            List<Object> scriptList = new ArrayList<>();
            for (SkillScript s : skill.getScripts()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", s.getName());
                m.put("language", s.getLanguage());
                m.put("description", s.getDescription());
                if (s.getParams() != null && !s.getParams().isEmpty()) {
                    List<Object> params = new ArrayList<>();
                    for (ScriptParam p : s.getParams()) {
                        Map<String, Object> pm = new LinkedHashMap<>();
                        pm.put("name", p.getName());
                        pm.put("type", p.getType());
                        pm.put("required", p.isRequired());
                        pm.put("description", p.getDescription());
                        params.add(pm);
                    }
                    m.put("params", params);
                }
                m.put("file", SCRIPTS_DIR + "/" + scriptFileName(s));
                scriptList.add(m);
            }
            meta.put("scripts", scriptList);
        }

        if (skill.getResources() != null && !skill.getResources().isEmpty()) {
            List<Object> resList = new ArrayList<>();
            for (SkillResource r : skill.getResources()) {
                if (r == null || r.getName() == null || r.getName().isBlank()) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", r.getName());
                m.put("file", REFERENCE_DIR + "/" + r.getName());
                resList.add(m);
            }
            if (!resList.isEmpty()) {
                meta.put("resources", resList);
            }
        }

        String frontMatter = yaml.dump(meta);
        String body = skill.getInstruction() == null ? "" : skill.getInstruction();
        return FRONT_MATTER_DELIMITER + "\n" + frontMatter + FRONT_MATTER_DELIMITER + "\n\n" + body;
    }

    /**
     * 解析 SKILL.md 为技能定义（读 front-matter 元数据 + 正文指令 + scripts/reference 文件内容）
     */
    private SkillDefinition readSkillMd(Path dir) throws IOException {
        String md = Files.readString(dir.resolve(SKILL_MD), StandardCharsets.UTF_8);
        int firstSep = md.indexOf(FRONT_MATTER_DELIMITER);
        if (firstSep < 0) {
            throw new IOException("SKILL.md 缺少 front-matter 分隔符: " + dir.getFileName());
        }
        int secondSep = md.indexOf(FRONT_MATTER_DELIMITER, firstSep + 3);
        if (secondSep < 0) {
            throw new IOException("SKILL.md 缺少 front-matter 结束分隔符: " + dir.getFileName());
        }
        String frontMatter = md.substring(firstSep + 3, secondSep);
        String body = md.substring(secondSep + 3).stripLeading();

        Map<String, Object> meta = yaml.load(frontMatter);
        if (meta == null) {
            throw new IOException("SKILL.md front-matter 解析为空: " + dir.getFileName());
        }
        String id = asString(meta.get("id"));
        if (id == null || id.isBlank()) {
            throw new IOException("SKILL.md 缺少 id: " + dir.getFileName());
        }

        SkillDefinition skill = new SkillDefinition();
        skill.setId(id);
        skill.setName(asString(meta.get("name")));
        skill.setDescription(asString(meta.get("description")));
        skill.setEnabled(asBoolean(meta.get("enabled"), true));
        skill.setSource(parseSource(asString(meta.get("source"))));
        skill.setInstruction(body);

        Object scriptsObj = meta.get("scripts");
        if (scriptsObj instanceof List<?> scriptList) {
            List<SkillScript> scripts = new ArrayList<>();
            for (Object o : scriptList) {
                if (!(o instanceof Map<?, ?> raw)) {
                    continue;
                }
                Map<String, Object> sm = castMap(raw);
                SkillScript s = new SkillScript();
                s.setName(asString(sm.get("name")));
                s.setLanguage(asString(sm.get("language")));
                s.setDescription(asString(sm.get("description")));
                Object paramsObj = sm.get("params");
                if (paramsObj instanceof List<?> paramList) {
                    List<ScriptParam> params = new ArrayList<>();
                    for (Object po : paramList) {
                        if (!(po instanceof Map<?, ?> rawP)) {
                            continue;
                        }
                        Map<String, Object> pm = castMap(rawP);
                        params.add(new ScriptParam(
                                asString(pm.get("name")),
                                asString(pm.get("type")),
                                asBoolean(pm.get("required"), false),
                                asString(pm.get("description"))));
                    }
                    s.setParams(params);
                }
                String file = asString(sm.get("file"));
                if (file != null && !file.isBlank()) {
                    s.setContent(Files.readString(dir.resolve(file), StandardCharsets.UTF_8));
                }
                scripts.add(s);
            }
            skill.setScripts(scripts);
        }

        Object resourcesObj = meta.get("resources");
        if (resourcesObj instanceof List<?> resList) {
            List<SkillResource> resources = new ArrayList<>();
            for (Object o : resList) {
                if (!(o instanceof Map<?, ?> raw)) {
                    continue;
                }
                Map<String, Object> rm = castMap(raw);
                String name = asString(rm.get("name"));
                String file = asString(rm.get("file"));
                if (name == null || name.isBlank() || file == null || file.isBlank()) {
                    continue;
                }
                resources.add(new SkillResource(name, Files.readString(dir.resolve(file), StandardCharsets.UTF_8)));
            }
            skill.setResources(resources);
        }
        return skill;
    }

    /**
     * 写脚本内容文件（scripts/{name}.{ext}）
     */
    private void writeScriptFiles(SkillDefinition skill, Path dir) throws IOException {
        if (skill.getScripts() == null) {
            return;
        }
        for (SkillScript s : skill.getScripts()) {
            if (s == null || s.getName() == null || s.getName().isBlank()) {
                continue;
            }
            if (s.getContent() != null) {
                Files.writeString(dir.resolve(SCRIPTS_DIR).resolve(scriptFileName(s)), s.getContent(), StandardCharsets.UTF_8);
            }
        }
    }

    /**
     * 写资源内容文件（reference/{name}）
     */
    private void writeResourceFiles(SkillDefinition skill, Path dir) throws IOException {
        if (skill.getResources() == null) {
            return;
        }
        for (SkillResource r : skill.getResources()) {
            if (r == null || r.getName() == null || r.getName().isBlank()) {
                continue;
            }
            if (r.getContent() != null) {
                Files.writeString(dir.resolve(REFERENCE_DIR).resolve(r.getName()), r.getContent(), StandardCharsets.UTF_8);
            }
        }
    }

    /**
     * 脚本文件名（按语言扩展名）
     */
    private String scriptFileName(SkillScript s) {
        String ext;
        if (s.getLanguage() != null && s.getLanguage().toLowerCase().contains("python")) {
            ext = ".py";
        } else {
            ext = ".sh";
        }
        return s.getName() + ext;
    }

    /**
     * 删除技能目录（含脚本/资源）及残留旧文件
     */
    private void deleteDir(String skillId) {
        synchronized (fileLock) {
            try {
                Path dir = storageDir.resolve(skillId);
                if (Files.isDirectory(dir)) {
                    try (var walk = Files.walk(dir)) {
                        walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException e) {
                                log.warn("技能目录删除失败: {}, 原因: {}", p, e.getMessage());
                            }
                        });
                    }
                }
                Files.deleteIfExists(storageDir.resolve(skillId + JSON_EXT));
                Files.deleteIfExists(storageDir.resolve(skillId + JSON_EXT + ".bak"));
            } catch (IOException e) {
                log.warn("技能目录删除失败（内存态已删除）: id={}, 原因: {}", skillId, e.getMessage());
            }
        }
    }

    private void requireId(SkillDefinition skill) {
        if (skill == null || skill.getId() == null || skill.getId().isBlank()) {
            throw new IllegalArgumentException("技能 id 不能为空");
        }
    }

    // ==================== 类型辅助 ====================

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            if (e.getKey() != null) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        return out;
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static boolean asBoolean(Object o, boolean defaultValue) {
        if (o == null) {
            return defaultValue;
        }
        if (o instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(o));
    }

    private static SkillSource parseSource(String source) {
        if (source == null) {
            return SkillSource.CUSTOM;
        }
        try {
            return SkillSource.valueOf(source.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return SkillSource.CUSTOM;
        }
    }
}
