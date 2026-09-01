package com.agentdemo.tools.sanitize;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SanitizeStage SPI 语义测试（CR-004 Task-23 接口契约 + Task-28 完整固化）
 * <p>
 * 业务含义：固化清洗管道变换段 SPI 的核心语义——order 排序执行、可插拔（新增 Stage
 * 零编排器改动接入，AC-T05）、逐段异常隔离（一段抛异常其余段继续，AC-E06）、
 * 门控 appliesTo 生效、终段③④不受链变化影响。测试桩 Stage 直接注入规范构造器
 * （List&lt;SanitizeStage&gt;），与 Spring 收集路径同构。
 * </p>
 */
class SanitizeStageChainTest {

    @TempDir
    Path tempDir;

    private ToolSanitizeProperties props;
    private ToolOutputTempStore store;

    @BeforeEach
    void setUp() {
        props = new ToolSanitizeProperties();
        props.setTempDir(tempDir.resolve("tool-output").toString());
        store = new ToolOutputTempStore(props, tempDir.toString());
    }

    private SanitizeContext ctx() {
        return SanitizeContext.builder().toolName("chainTest").sourceDesc("测试来源").htmlContent(false).build();
    }

    /** 可控桩段：order/段名/门控/变换函数均可指定 */
    private SanitizeStage stage(int order, String name, boolean applies, UnaryOperator<String> fn) {
        return new SanitizeStage() {
            @Override
            public int order() {
                return order;
            }

            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean appliesTo(SanitizeContext c) {
                return applies;
            }

            @Override
            public String process(String text, SanitizeContext c) {
                return fn.apply(text);
            }
        };
    }

    @Test
    void 默认appliesTo为无条件执行() {
        SanitizeStage stage = stage(100, "TEST_STAGE", true, t -> t);
        assertTrue(stage.appliesTo(ctx()), "未覆写 appliesTo 时应无条件执行（默认 true）");
    }

    @Test
    void 按order升序执行链() {
        List<String> trace = new ArrayList<>();
        SanitizeStage late = stage(300, "S3", true, t -> {
            trace.add("S3");
            return t;
        });
        SanitizeStage first = stage(100, "S1", true, t -> {
            trace.add("S1");
            return t;
        });
        SanitizeStage middle = stage(200, "S2", true, t -> {
            trace.add("S2");
            return t;
        });
        // 乱序注册：S3/S1/S2
        ToolOutputSanitizer sanitizer = new ToolOutputSanitizer(props, List.of(late, first, middle), store);

        sanitizer.sanitize("正文", ctx());
        assertEquals(List.of("S1", "S2", "S3"), trace, "乱序注册的段应按 order 升序执行");
    }

    @Test
    void 新增Stage零编排器改动接入且产物经终段包裹() {
        // AC-T05 核心断言：新增一个 order=500 的检测类 Stage（模拟 CR-002 接入形态），
        // 编排器零修改，其产物流入固定终段③④
        SanitizeStage newDetector = stage(500, "MOCK_DETECTOR", true, t -> t + "\n[风险分: 0.9]");
        ToolOutputSanitizer sanitizer = new ToolOutputSanitizer(props, List.of(newDetector), store);

        String result = sanitizer.sanitize("正文", ctx());
        assertTrue(result.contains("[风险分: 0.9]"), "新增段产物应进入管道");
        assertTrue(result.contains("===BEGIN_TOOL_DATA"), "新增段产物应经终段④包裹声明");
    }

    @Test
    void 单段异常跳过且后续段继续() {
        SanitizeStage broken = stage(100, "BROKEN", true, t -> {
            throw new RuntimeException("桩段爆炸");
        });
        AtomicBoolean laterRan = new AtomicBoolean(false);
        SanitizeStage later = stage(200, "LATER", true, t -> {
            laterRan.set(true);
            return t + "!";
        });
        ToolOutputSanitizer sanitizer = new ToolOutputSanitizer(props, List.of(broken, later), store);

        String result = sanitizer.sanitize("正文", ctx());
        assertTrue(laterRan.get(), "异常段之后的段应继续执行（AC-E06 逐段隔离）");
        assertTrue(result.contains("正文!"), "后续段产物应进入终段包裹");
    }

    @Test
    void 门控关闭的段被跳过() {
        SanitizeStage gatedOff = stage(100, "OFF", false, t -> t + "[不应出现]");
        SanitizeStage gatedOn = stage(200, "ON", true, t -> t + "[已执行]");
        ToolOutputSanitizer sanitizer = new ToolOutputSanitizer(props, List.of(gatedOff, gatedOn), store);

        String result = sanitizer.sanitize("正文", ctx());
        assertFalse(result.contains("[不应出现]"), "appliesTo=false 的段应被跳过（门控归一）");
        assertTrue(result.contains("[已执行]"), "门控开启的段应正常执行");
    }

    @Test
    void 终段限长包裹不受链变化影响() {
        props.setMaxChars(10);
        SanitizeStage stage = stage(100, "APPENDER", true, t -> t + "B".repeat(50));
        ToolOutputSanitizer sanitizer = new ToolOutputSanitizer(props, List.of(stage), store);

        String result = sanitizer.sanitize("A".repeat(5), ctx());
        assertTrue(result.contains("[结果过长已截断]"), "变换段拉长内容后仍应触发终段③限长（AC-T01）");
        assertTrue(result.contains("===BEGIN_TOOL_DATA"), "终段④包裹不受链变化影响（技术决策 12）");
        assertFalse(result.contains("B".repeat(30)), "超限部分不应进入上下文（前缀保留）");
    }
}
