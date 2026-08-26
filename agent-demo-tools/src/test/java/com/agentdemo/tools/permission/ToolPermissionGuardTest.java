package com.agentdemo.tools.permission;

import com.agentdemo.tools.builtin.CalculatorTool;
import com.agentdemo.tools.builtin.FileReadTool;
import com.agentdemo.tools.registry.ToolIdResolver;
import com.agentdemo.tools.sanitize.ToolOutputSanitizer;
import com.agentdemo.tools.sanitize.ToolSanitizeProperties;
import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.agentdemo.tools.permission.ToolPermissionLevel.ALLOW;
import static com.agentdemo.tools.permission.ToolPermissionLevel.ASK;
import static com.agentdemo.tools.permission.ToolPermissionLevel.DENY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Task-03: ToolPermissionGuard 包装层测试
 * <p>
 * 业务含义：验证执行期 deny 零触发防线（AC-T03）——包装对象按解析时捕获的权限等级拦截：
 * deny 方法体零触发返回固定拒绝文案；allow 委托原方法；ask 防御拒绝。权限变更不中途突变
 * （AC-M01），同一对象同版本复用包装实例，权限变更后下一轮生成新实例。
 * </p>
 */
class ToolPermissionGuardTest {

    @TempDir
    Path tempDir;

    private ToolPermissionProperties props;
    private ToolPermissionService permissionService;
    private ToolPermissionGuard guard;

    @BeforeEach
    void setUp() {
        props = new ToolPermissionProperties();
        props.setFilePath(tempDir.resolve("perm.json").toString());
        permissionService = new ToolPermissionService(props);
        guard = new ToolPermissionGuard(permissionService, new ToolIdResolver());
    }

    @Test
    @DisplayName("deny 工具：调用返回固定拒绝文案，原方法体零触发（AC-T03 第二道防线）")
    void denyToolReturnsDenyMessageAndZeroInvocation() {
        // 业务含义：即使 deny 工具被绕过加载期过滤注入，包装层也拦截，方法体绝不执行
        DenyTool original = spy(new DenyTool());
        permissionService.setExplicit("builtin:deniedAction", DENY);

        Object wrapper = guard.wrap(original);
        String result = invoke(wrapper, "deniedAction");

        assertThat(result).isEqualTo(ToolPermissionGuard.DENY_MESSAGE);
        verify(original, never()).deniedAction();   // 方法体零触发
    }

    @Test
    @DisplayName("allow 工具：委托原方法执行，结果与直调一致（AC-T04 执行链路统一）")
    void allowToolDelegatesToOriginal() throws Exception {
        CalculatorTool original = new CalculatorTool();
        permissionService.registerDefault("builtin:calculate", ALLOW);

        Object wrapper = guard.wrap(original);
        String result = invoke(wrapper, "calculate", "2+3");

        assertThat(result).isEqualTo("2+3 = 5");
    }

    @Test
    @DisplayName("ask 工具：包装层防御拒绝（理论不可达直调路径）")
    void askToolDefensiveReject() {
        AskTool original = spy(new AskTool());
        permissionService.registerDefault("builtin:askAction", ASK);

        Object wrapper = guard.wrap(original);
        String result = invoke(wrapper, "askAction");

        assertThat(result).isEqualTo("该工具需要用户确认后方可调用，但当前执行路径未提供确认通道。");
        verify(original, never()).askAction();   // ask 在直调路径也应零触发（确认走 HITLReActStream 手动路径）
    }

    @Test
    @DisplayName("权限等级解析时捕获：包装后变更权限，包装实例行为不变（AC-M01 不中途突变）")
    void capturedLevelStableAfterWrap() {
        DenyTool original = spy(new DenyTool());
        permissionService.setExplicit("builtin:deniedAction", DENY);

        Object wrapper = guard.wrap(original);

        // 包装后把权限改为 allow，包装实例仍按解析时 DENY 拦截
        permissionService.setExplicit("builtin:deniedAction", ALLOW);
        String result = invoke(wrapper, "deniedAction");

        assertThat(result).isEqualTo(ToolPermissionGuard.DENY_MESSAGE);
        verify(original, never()).deniedAction();
    }

    @Test
    @DisplayName("同一对象同一权限版本重复 wrap 返回同一包装实例（缓存生效）")
    void sameVersionReusesWrapperInstance() {
        CalculatorTool original = new CalculatorTool();
        permissionService.registerDefault("builtin:calculate", ALLOW);

        Object wrapper1 = guard.wrap(original);
        Object wrapper2 = guard.wrap(original);

        assertThat(wrapper1).isSameAs(wrapper2);
        assertThat(wrapper1).isNotSameAs(original);
    }

    @Test
    @DisplayName("权限变更版本递增：下一轮 wrap 生成新实例反映新权限（AC-M01 下轮生效）")
    void versionChangeRebuildsWrapper() {
        DenyTool original = spy(new DenyTool());
        permissionService.setExplicit("builtin:deniedAction", DENY);
        Object denyWrapper = guard.wrap(original);

        // 权限变更（deny → allow）后重新解析（wrap）
        permissionService.setExplicit("builtin:deniedAction", ALLOW);
        Object allowWrapper = guard.wrap(original);

        assertThat(allowWrapper).isNotSameAs(denyWrapper);
        String result = invoke(allowWrapper, "deniedAction");
        assertThat(result).isEqualTo("should not run");   // 新实例按 allow 委托原方法
        verify(original).deniedAction();
    }

    @Test
    @DisplayName("权限功能关闭（enabled=false）时包装直通委托（回滚开关）")
    void disabledGuardDelegatesDirectly() {
        props.setEnabled(false);   // 回滚开关：权限服务返回 ALLOW
        DenyTool original = spy(new DenyTool());

        Object wrapper = guard.wrap(original);
        String result = invoke(wrapper, "deniedAction");

        assertThat(result).isEqualTo("should not run");
        verify(original).deniedAction();
    }

    @Test
    @DisplayName("无 @Tool 方法的对象原样返回（无需包装）")
    void nonToolObjectReturnedAsIs() {
        Object plain = new Object();
        assertThat(guard.wrap(plain)).isSameAs(plain);
    }

    @Test
    @DisplayName("final 类工具也能正常包装（方案 B subclass Object + 反射委托不受 final 限制）")
    void finalClassCanAlsoBeWrapped() {
        // 业务含义：包装类是 Object 的子类（非继承工具类），final 工具类通过反射委托仍可包装
        permissionService.registerDefault("builtin:finalAction", ALLOW);   // 登记 ALLOW，聚焦验证 final 可包装
        FinalTool original = new FinalTool();
        Object wrapper = guard.wrap(original);
        assertThat(wrapper).isNotSameAs(original);
        assertThat(invoke(wrapper, "finalAction")).isEqualTo("final");
    }

    @Test
    @DisplayName("包装类方法名/参数名/@Tool 注解与原方法一致（LangChain4j schema 保真）")
    void wrapperSchemaFidelity() {
        CalculatorTool original = new CalculatorTool();
        permissionService.registerDefault("builtin:calculate", ALLOW);

        Object wrapper = guard.wrap(original);
        Class<?> wrapperClass = wrapper.getClass();

        assertThat(wrapperClass.getSimpleName()).isEqualTo("CalculatorTool$PermissionGuard");
        try {
            java.lang.reflect.Method m = wrapperClass.getMethod("calculate", String.class);
            assertThat(m.getAnnotation(Tool.class)).isNotNull();
            assertThat(m.getParameters()[0].getName()).isEqualTo("expression");
        } catch (NoSuchMethodException e) {
            throw new AssertionError("包装类应包含 calculate(String) 方法", e);
        }
    }

    @Test
    @DisplayName("扩展签名后的 FileReadTool 包装保真：readFile 三参数/参数名/描述一致（T14 回归）")
    void wrapperSchemaFidelityForExtendedFileReadTool() throws Exception {
        // 业务含义：readFile 签名扩展为 (path, offset, maxChars) 后，ByteBuddy 包装层
        // 必须按新签名生成包装方法，参数名/描述与原方法逐字段一致（LangChain4j ToolSpecification 保真）
        Path dataDir = tempDir.resolve("data");
        Files.createDirectories(dataDir);
        FileReadTool original = new FileReadTool(
                ToolOutputSanitizer.disabled(), new ToolSanitizeProperties(), dataDir.toString());
        permissionService.registerDefault("builtin:readFile", ALLOW);

        Object wrapper = guard.wrap(original);
        Class<?> wrapperClass = wrapper.getClass();

        assertThat(wrapperClass.getSimpleName()).isEqualTo("FileReadTool$PermissionGuard");
        java.lang.reflect.Method wrapped = wrapperClass.getMethod("readFile", String.class, Integer.class, Integer.class);
        assertThat(wrapped.getAnnotation(Tool.class)).isNotNull();
        assertThat(wrapped.getParameters()[0].getName()).isEqualTo("path");
        assertThat(wrapped.getParameters()[1].getName()).isEqualTo("offset");
        assertThat(wrapped.getParameters()[2].getName()).isEqualTo("maxChars");
        // 描述（@Tool 注解值）与原方法一致
        java.lang.reflect.Method originalMethod = FileReadTool.class
                .getMethod("readFile", String.class, Integer.class, Integer.class);
        assertThat(String.join(" ", wrapped.getAnnotation(Tool.class).value()))
                .isEqualTo(String.join(" ", originalMethod.getAnnotation(Tool.class).value()));

        // ALLOW 委托路径：分页读取正常返回（直通清洗器，结果与直调一致）
        Files.writeString(dataDir.resolve("a.txt"), "hello world", StandardCharsets.UTF_8);
        String result = invoke(wrapper, "readFile", "a.txt", Integer.valueOf(0), Integer.valueOf(5));
        assertThat(result).isEqualTo("hello world");
    }

    // ==================== 测试辅助 ====================

    private String invoke(Object target, String methodName, Object... args) {
        try {
            Class<?>[] paramTypes = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                paramTypes[i] = args[i] != null ? args[i].getClass() : String.class;
            }
            java.lang.reflect.Method method = target.getClass().getMethod(methodName, paramTypes);
            Object result = method.invoke(target, args);
            return result != null ? result.toString() : null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** deny 工具（显式置 DENY） */
    static class DenyTool {
        @Tool("deny 工具")
        public String deniedAction() {
            return "should not run";
        }
    }

    /** ask 工具（默认 ASK） */
    static class AskTool {
        @Tool("ask 工具")
        public String askAction() {
            return "ask";
        }
    }

    /** final 类工具（验证生成失败降级） */
    static final class FinalTool {
        @Tool("final 工具")
        public String finalAction() {
            return "final";
        }
    }
}
