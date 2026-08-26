package com.agentdemo.tools.permission;

import com.agentdemo.tools.builtin.CalculatorTool;
import com.agentdemo.tools.builtin.FileReadTool;
import com.agentdemo.tools.builtin.HttpTool;
import com.agentdemo.tools.builtin.TimeTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具权限核心单元测试
 * <p>
 * 业务含义：验证权限等级模型（枚举/注解/配置）、权限查询四规则（显式配置→注册默认→保守兜底 + askUser 豁免）、
 * JSON 持久化降级策略、内置工具默认分级标注（Task-01 ~ Task-04）。
 * </p>
 */
class ToolPermissionServiceTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 构造一个使用隔离临时目录的权限服务 */
    private ToolPermissionService newService() {
        ToolPermissionProperties props = new ToolPermissionProperties();
        props.setFilePath(tempDir.resolve("perm.json").toString());
        return new ToolPermissionService(props);
    }

    // ==================== Task-01: 枚举 / 注解 / 配置属性 ====================

    @Test
    @DisplayName("枚举含 ALLOW/ASK/DENY 且可从字符串大小写不敏感解析")
    void enumContainsThreeLevelsAndParsesCaseInsensitive() {
        assertEquals(3, ToolPermissionLevel.values().length, "枚举应含 3 个等级");
        assertEquals(ToolPermissionLevel.ALLOW, ToolPermissionLevel.parse("allow"));
        assertEquals(ToolPermissionLevel.ASK, ToolPermissionLevel.parse("ASK"));
        assertEquals(ToolPermissionLevel.DENY, ToolPermissionLevel.parse("Deny"));
        assertEquals(ToolPermissionLevel.ALLOW, ToolPermissionLevel.parse(" ALLOW "));
    }

    @Test
    @DisplayName("解析非法等级字符串抛出 IllegalArgumentException")
    void enumParseRejectsInvalidValue() {
        assertThrows(IllegalArgumentException.class, () -> ToolPermissionLevel.parse("unknown"));
        assertEquals(null, ToolPermissionLevel.parse(null), "null 解析应返回 null 而非异常");
    }

    @Test
    @DisplayName("默认权限注解标注在类型上且 RUNTIME 保留")
    void defaultPermissionAnnotationTargetsTypeAndRetainsRuntime() {
        Class<DefaultToolPermission> annotationType = DefaultToolPermission.class;

        assertTrue(annotationType.isAnnotationPresent(Target.class), "应标注 @Target");
        Target target = annotationType.getAnnotation(Target.class);
        assertTrue(Arrays.asList(target.value()).contains(ElementType.TYPE), "应允许标注在类型上");

        assertTrue(annotationType.isAnnotationPresent(Retention.class), "应标注 @Retention");
        assertEquals(RetentionPolicy.RUNTIME,
                annotationType.getAnnotation(Retention.class).value(), "应 RUNTIME 保留以便反射读取");
    }

    @Test
    @DisplayName("配置属性默认值：filePath=data/tool-permissions.json，enabled=true")
    void propertiesDefaultsAreConfigured() {
        ToolPermissionProperties props = new ToolPermissionProperties();
        assertEquals("data/tool-permissions.json", props.getFilePath());
        assertTrue(props.isEnabled(), "权限功能默认开启");
    }

    // ==================== Task-02: 权限查询服务 ====================

    @Test
    @DisplayName("未注册未配置的未知 toolId 查询返回 ASK（保守兜底）")
    void unknownToolIdReturnsAskConservativeFallback() {
        ToolPermissionService service = newService();
        assertEquals(ToolPermissionLevel.ASK, service.getPermission("builtin:unknown"));
        assertEquals(ToolPermissionLevel.ASK, service.getPermission(null));
    }

    @Test
    @DisplayName("registerDefault 后查询返回注册的默认等级")
    void registerDefaultThenQueryReturnsRegisteredLevel() {
        ToolPermissionService service = newService();
        service.registerDefault("builtin:calculator", ToolPermissionLevel.ALLOW);
        assertEquals(ToolPermissionLevel.ALLOW, service.getPermission("builtin:calculator"));
    }

    @Test
    @DisplayName("显式配置覆盖注册默认值")
    void explicitOverridesDefault() {
        ToolPermissionService service = newService();
        service.registerDefault("builtin:calculator", ToolPermissionLevel.ALLOW);
        service.setExplicit("builtin:calculator", ToolPermissionLevel.DENY);
        assertEquals(ToolPermissionLevel.DENY, service.getPermission("builtin:calculator"));
    }

    @Test
    @DisplayName("askUser 工具无论何种配置/注册状态均返回 ALLOW（豁免）")
    void askUserIsAlwaysAllowed() {
        ToolPermissionService service = newService();
        service.registerDefault("builtin:askUser", ToolPermissionLevel.DENY);
        service.setExplicit("builtin:askUser", ToolPermissionLevel.DENY);
        assertEquals(ToolPermissionLevel.ALLOW, service.getPermission("builtin:askUser"));
    }

    @Test
    @DisplayName("clear 后该 toolId 查询回落到兜底 ASK")
    void clearReturnsToFallback() {
        ToolPermissionService service = newService();
        service.registerDefault("mcp:x", ToolPermissionLevel.ASK);
        service.setExplicit("mcp:x", ToolPermissionLevel.DENY);
        service.clear(List.of("mcp:x"));
        assertEquals(ToolPermissionLevel.ASK, service.getPermission("mcp:x"));
    }

    @Test
    @DisplayName("enabled=false 时查询一律返回 ALLOW（功能降级开关）")
    void disabledReturnsAllowForEverything() {
        ToolPermissionProperties props = new ToolPermissionProperties();
        props.setEnabled(false);
        props.setFilePath(tempDir.resolve("perm.json").toString());
        ToolPermissionService service = new ToolPermissionService(props);

        service.registerDefault("builtin:calculator", ToolPermissionLevel.DENY);
        service.setExplicit("builtin:calculator", ToolPermissionLevel.DENY);
        assertEquals(ToolPermissionLevel.ALLOW, service.getPermission("builtin:calculator"));
        assertEquals(ToolPermissionLevel.ALLOW, service.getPermission("builtin:askUser"));
        assertEquals(ToolPermissionLevel.ALLOW, service.getPermission("mcp:unknown"));
    }

    // ==================== Task-03: JSON 持久化 ====================

    @Test
    @DisplayName("setExplicit 后生成合法 JSON 文件（toolId → 小写等级字符串）")
    void setExplicitPersistsToJsonFile() throws Exception {
        ToolPermissionService service = newService();
        service.setExplicit("builtin:http", ToolPermissionLevel.ASK);

        Path file = tempDir.resolve("perm.json");
        assertTrue(Files.exists(file), "setExplicit 后应生成权限文件");
        JsonNode node = objectMapper.readTree(Files.readString(file));
        assertEquals("ask", node.get("builtin:http").asText(), "文件内容应为 toolId → 小写等级");
    }

    @Test
    @DisplayName("重新构造服务实例（模拟重启）加载文件后显式配置与重启前一致")
    void reloadAfterRestartKeepsExplicitConfig() throws Exception {
        Path file = tempDir.resolve("perm.json");

        ToolPermissionProperties props1 = new ToolPermissionProperties();
        props1.setFilePath(file.toString());
        new ToolPermissionService(props1).setExplicit("builtin:http", ToolPermissionLevel.ASK);
        new ToolPermissionService(props1).setExplicit("mcp:mermaid", ToolPermissionLevel.ALLOW);

        // 模拟重启：全新服务实例从文件加载
        ToolPermissionProperties props2 = new ToolPermissionProperties();
        props2.setFilePath(file.toString());
        ToolPermissionService service2 = new ToolPermissionService(props2);

        assertEquals(ToolPermissionLevel.ASK, service2.getPermission("builtin:http"));
        assertEquals(ToolPermissionLevel.ALLOW, service2.getPermission("mcp:mermaid"));
    }

    @Test
    @DisplayName("文件不存在时启动正常，全部按默认规则")
    void missingFileStartsWithEmptyConfig() {
        ToolPermissionProperties props = new ToolPermissionProperties();
        props.setFilePath(tempDir.resolve("nonexistent").resolve("perm.json").toString());

        ToolPermissionService service = assertDoesNotThrow(() -> new ToolPermissionService(props));
        assertEquals(ToolPermissionLevel.ASK, service.getPermission("builtin:http"));
    }

    @Test
    @DisplayName("文件内容非法 JSON 时启动不抛异常，降级为空配置")
    void invalidJsonFileDegradesToEmptyConfig() throws Exception {
        Files.writeString(tempDir.resolve("perm.json"), "{invalid json");

        ToolPermissionProperties props = new ToolPermissionProperties();
        props.setFilePath(tempDir.resolve("perm.json").toString());

        ToolPermissionService service = assertDoesNotThrow(() -> new ToolPermissionService(props));
        assertEquals(ToolPermissionLevel.ASK, service.getPermission("builtin:http"));
    }

    @Test
    @DisplayName("写文件异常（父目录为文件）时 setExplicit 不抛异常，内存权限仍生效")
    void writeFailureDoesNotThrowAndMemoryStillEffective() throws Exception {
        // 让权限文件的父目录是一个普通文件，导致 createDirectories 抛异常模拟写失败
        Path parentAsFile = tempDir.resolve("notadir");
        Files.writeString(parentAsFile, "i am a file");

        ToolPermissionProperties props = new ToolPermissionProperties();
        props.setFilePath(parentAsFile.resolve("perm.json").toString());
        ToolPermissionService service = new ToolPermissionService(props);

        assertDoesNotThrow(() -> service.setExplicit("builtin:http", ToolPermissionLevel.ASK));
        assertEquals(ToolPermissionLevel.ASK, service.getPermission("builtin:http"), "写失败时内存权限仍生效");
    }

    @Test
    @DisplayName("clear 清理显式配置时同步更新持久化文件")
    void clearPersistsRemoval() throws Exception {
        ToolPermissionService service = newService();
        service.setExplicit("builtin:http", ToolPermissionLevel.ASK);

        service.clear(List.of("builtin:http"));

        JsonNode node = objectMapper.readTree(Files.readString(tempDir.resolve("perm.json")));
        assertTrue(node.isEmpty(), "clear 后文件中不应残留该工具配置");
    }

    // ==================== Task-04: 内置工具默认权限标注 ====================

    @Test
    @DisplayName("四个内置工具类注解标注正确（反射断言）")
    void builtinToolsHaveCorrectDefaultPermissionAnnotation() {
        assertEquals(ToolPermissionLevel.ALLOW, CalculatorTool.class.getAnnotation(DefaultToolPermission.class).value());
        assertEquals(ToolPermissionLevel.ALLOW, TimeTool.class.getAnnotation(DefaultToolPermission.class).value());
        assertEquals(ToolPermissionLevel.ASK, HttpTool.class.getAnnotation(DefaultToolPermission.class).value());
        assertEquals(ToolPermissionLevel.ASK, FileReadTool.class.getAnnotation(DefaultToolPermission.class).value());
    }
}
