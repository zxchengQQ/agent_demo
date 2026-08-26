package com.agentdemo.tools.permission;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 工具权限服务
 * <p>
 * 业务含义：全工具域唯一的权限查询门面。任何工具在"能否被加载 / 能否被执行"前都先问它，
 * 它按固定优先级给出 allow / ask / deny 裁决。
 * </p>
 * <p>
 * 查询优先级（技术方案 §3.1）：显式配置 → 注册默认（注解或类别兜底） → ASK 保守兜底；
 * askUser 工具（builtin:askUser）特判豁免，永远返回 ALLOW（AC-S03 防确认死锁）。
 * </p>
 * <p>
 * 存储：内存两张表（显式配置 / 注册默认）+ 显式配置 JSON 文件持久化（重启不丢，AC-N01）。
 * 持久化降级：文件不存在→空配置；文件损坏→空配置全默认；写失败→WARN 不阻断，内存态仍生效。
 * </p>
 * <p>
 * 设计约束：本服务不依赖 ToolRegistry，构造期仅加载权限文件（无 Bean 扫描），避免循环依赖
 * （项目历史教训：SimpleAgent ↔ ToolRegistry 构造期循环）。默认等级由 ToolRegistry 注册时登记。
 * </p>
 */
@Component
public class ToolPermissionService {

    private static final Logger log = LoggerFactory.getLogger(ToolPermissionService.class);

    /** askUser 工具豁免特判标识（AC-S03） */
    public static final String ASK_USER_TOOL_ID = "builtin:askUser";

    private final ToolPermissionProperties properties;
    private final ObjectMapper objectMapper;
    private final Path permissionFile;

    /** 管理员显式配置（toolId → level），持久化到 JSON 文件 */
    private final Map<String, ToolPermissionLevel> explicitPermissions = new ConcurrentHashMap<>();

    /** 注册时登记的默认等级（来自注解或类别兜底），内存态不持久化 */
    private final Map<String, ToolPermissionLevel> defaultPermissions = new ConcurrentHashMap<>();

    /** 文件写锁（显式配置变更频率极低，同步写 + 对象锁足够，不过度设计） */
    private final Object fileLock = new Object();

    /**
     * 权限版本号：每次显式配置变更递增。
     * 业务含义：供 ToolPermissionGuard 包装实例缓存按版本失效——同版本复用包装实例
     * （本轮工具列表稳定），权限变更后版本递增，下一次解析生成新包装实例（AC-M01 下轮生效）。
     */
    private final AtomicLong version = new AtomicLong();

    public ToolPermissionService(ToolPermissionProperties properties) {
        this.properties = properties;
        this.objectMapper = new ObjectMapper();
        this.permissionFile = Path.of(properties.getFilePath()).toAbsolutePath().normalize();
        loadFromFile();
    }

    /**
     * 查询工具权限等级
     * <p>
     * 业务含义：按"显式配置 → 注册默认 → ASK 保守兜底"的优先级裁决；askUser 豁免。
     * 权限功能关闭（enabled=false）时一律放行（回滚开关）。
     * </p>
     *
     * @param toolId 工具标识（category:name 格式）
     * @return 权限等级（永不返回 null）
     */
    public ToolPermissionLevel getPermission(String toolId) {
        if (!properties.isEnabled()) {
            return ToolPermissionLevel.ALLOW;
        }
        if (ASK_USER_TOOL_ID.equals(toolId)) {
            return ToolPermissionLevel.ALLOW;
        }
        if (toolId == null) {
            return ToolPermissionLevel.ASK;
        }
        ToolPermissionLevel explicit = explicitPermissions.get(toolId);
        if (explicit != null) {
            return explicit;
        }
        ToolPermissionLevel def = defaultPermissions.get(toolId);
        if (def != null) {
            return def;
        }
        return ToolPermissionLevel.ASK;
    }

    /**
     * 登记工具默认权限等级
     * <p>
     * 业务含义：由 ToolRegistry 在扫描/动态注册时调用，登记注解值或类别兜底得到的默认等级。
     * 显式配置优先于默认等级，因此登记不覆盖已存在的显式配置。
     * </p>
     *
     * @param toolId 工具标识
     * @param level  默认权限等级
     */
    public void registerDefault(String toolId, ToolPermissionLevel level) {
        if (toolId == null || toolId.isBlank() || level == null) {
            return;
        }
        defaultPermissions.put(toolId, level);
    }

    /**
     * 设置显式权限配置并持久化
     * <p>
     * 业务含义：管理 API 变更权限的落点。内存先更新，随后同步写 JSON 文件；
     * 写失败仅记 WARN（内存态仍生效，下次任何成功变更会重新写入）。
     * </p>
     *
     * @param toolId 工具标识
     * @param level  目标权限等级
     */
    public void setExplicit(String toolId, ToolPermissionLevel level) {
        if (toolId == null || toolId.isBlank() || level == null) {
            return;
        }
        explicitPermissions.put(toolId, level);
        version.incrementAndGet();   // 显式配置变更触发版本递增（包装实例缓存失效）
        persistToFile();
    }

    /**
     * 清理指定工具的权限登记
     * <p>
     * 业务含义：由 ToolRegistry 在注销工具时调用（KB/MCP 删除路径自动覆盖），
     * 清除显式配置与默认登记，避免孤儿权限配置（AC-E01）。
     * </p>
     *
     * @param toolIds 待清理的工具标识列表
     */
    public void clear(List<String> toolIds) {
        if (toolIds == null) {
            return;
        }
        boolean removed = false;
        for (String toolId : toolIds) {
            if (toolId == null) {
                continue;
            }
            removed |= (explicitPermissions.remove(toolId) != null);
            defaultPermissions.remove(toolId);
        }
        // 显式配置有实际删除时才同步持久化文件
        if (removed) {
            persistToFile();
        }
    }

    /**
     * 权限功能是否开启（回滚开关查询）
     */
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * 当前权限版本号（供包装实例缓存按版本失效）
     */
    public long getVersion() {
        return version.get();
    }

    // ==================== 持久化 ====================

    /**
     * 启动时加载显式权限配置
     * <p>
     * 业务含义：文件不存在→静默空配置；文件损坏/不可读→WARN 降级空配置（全部按默认规则），不阻断服务启动。
     * </p>
     */
    private void loadFromFile() {
        if (!Files.exists(permissionFile)) {
            return;
        }
        try {
            Map<String, ToolPermissionLevel> loaded = objectMapper.readValue(
                    permissionFile.toFile(), new TypeReference<Map<String, ToolPermissionLevel>>() {});
            if (loaded != null) {
                loaded.forEach((toolId, level) -> {
                    if (toolId != null && level != null) {
                        explicitPermissions.put(toolId, level);
                    }
                });
                log.info("已加载工具权限配置 {} 条: {}", explicitPermissions.size(), permissionFile);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("工具权限配置文件加载失败，降级为空配置: {}", permissionFile, e);
        }
    }

    /**
     * 同步写显式配置到 JSON 文件
     * <p>
     * 业务含义：仅存显式配置（defaultPermissions 为内存态）；写失败降级 WARN 不阻断。
     * </p>
     */
    private void persistToFile() {
        synchronized (fileLock) {
            try {
                Path parent = permissionFile.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                String json = objectMapper.writeValueAsString(explicitPermissions);
                Files.writeString(permissionFile, json, StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                log.warn("工具权限配置持久化失败（内存态仍生效）: {}", permissionFile, e);
            }
        }
    }
}
