package com.agentdemo.tools.sanitize;

import com.agentdemo.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolOutputTempStore 单元测试
 * <p>
 * 验证标准来源：Task-05 验证标准
 * 业务含义：验证超长内容临时文件写入（AC-T01）、相对路径生成（供模型 readFile 回读，
 * AC-T02）、机会式过期清理与写失败异常信号（AC-E01 管道据此降级纯截断）。
 * </p>
 */
class ToolOutputTempStoreTest {

    @TempDir
    Path tempDir;

    private ToolOutputTempStore newStore(String allowedDir, String storeDir) {
        ToolSanitizeProperties props = new ToolSanitizeProperties();
        props.setTempDir(storeDir);
        return new ToolOutputTempStore(props, allowedDir);
    }

    @Test
    void store落盘成功且文件位于临时目录() throws IOException {
        Path allowedRoot = tempDir.resolve("data").toAbsolutePath();
        Path storeDir = allowedRoot.resolve("tool-output").toAbsolutePath();
        ToolOutputTempStore store = newStore(allowedRoot.toString(), storeDir.toString());

        TempFileRecord record = store.store("这是一段超长内容".repeat(100), "httpGet");

        assertTrue(Files.exists(Path.of(record.getAbsolutePath())), "临时文件应已落盘");
        assertTrue(record.getAbsolutePath().contains("httpGet"), "文件名应含来源工具名");
        assertTrue(record.getAbsolutePath().endsWith(".txt"), "文件扩展名应为 .txt");
        assertTrue(record.getRelativePath().startsWith("tool-output/"),
                "相对路径应指向 allowedRoot 下的 tool-output 目录，实际: " + record.getRelativePath());
    }

    @Test
    void 文件头元信息正确() throws IOException {
        Path allowedRoot = tempDir.resolve("data").toAbsolutePath();
        Path storeDir = allowedRoot.resolve("tool-output").toAbsolutePath();
        ToolOutputTempStore store = newStore(allowedRoot.toString(), storeDir.toString());
        String content = "清洗后的完整内容";
        TempFileRecord record = store.store(content, "readFile");

        String fileContent = Files.readString(Path.of(record.getAbsolutePath()), StandardCharsets.UTF_8);
        String[] lines = fileContent.split("\\R", -1);
        assertTrue(lines[0].startsWith("# SOURCE=readFile"), "第 1 行应为来源元信息");
        assertTrue(lines[1].startsWith("# ORIGINAL_LENGTH=" + content.length()), "第 2 行应为原始长度元信息");
        assertTrue(fileContent.endsWith(content), "文件尾部应为清洗后的完整正文");
    }

    @Test
    void 相对路径可被resolveAndValidate解析回绝对路径() throws IOException {
        Path allowedRoot = tempDir.resolve("data").toAbsolutePath();
        Path storeDir = allowedRoot.resolve("tool-output").toAbsolutePath();
        ToolOutputTempStore store = newStore(allowedRoot.toString(), storeDir.toString());
        TempFileRecord record = store.store("内容", "httpGet");

        Path resolved = store.resolveAndValidate(record.getRelativePath());
        assertEquals(Path.of(record.getAbsolutePath()), resolved, "相对路径应解析回原绝对路径");
    }

    @Test
    void resolveAndValidate对越界路径抛异常() {
        Path allowedRoot = tempDir.resolve("data").toAbsolutePath();
        Path storeDir = allowedRoot.resolve("tool-output").toAbsolutePath();
        ToolOutputTempStore store = newStore(allowedRoot.toString(), storeDir.toString());

        assertThrows(BusinessException.class,
                () -> store.resolveAndValidate("../../etc/passwd"), "越界路径应被拒绝");
        assertThrows(BusinessException.class,
                () -> store.resolveAndValidate("other/not-in-temp-dir.txt"), "临时目录外路径应被拒绝");
    }

    @Test
    void 过期文件在写入时被清理() throws IOException {
        Path allowedRoot = tempDir.resolve("data").toAbsolutePath();
        Path storeDir = allowedRoot.resolve("tool-output").toAbsolutePath();
        ToolOutputTempStore store = newStore(allowedRoot.toString(), storeDir.toString());

        // 预置一个过期文件（25 小时前）与一个新鲜文件
        Files.createDirectories(storeDir);
        Path stale = storeDir.resolve("stale_old.txt");
        Files.writeString(stale, "旧内容");
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minus(25, ChronoUnit.HOURS)));
        Path fresh = storeDir.resolve("fresh_new.txt");
        Files.writeString(fresh, "新内容");

        store.store("新写入内容", "httpGet");

        assertFalse(Files.exists(stale), "超过保留时长的文件应被清理");
        assertTrue(Files.exists(fresh), "未超期的文件应保留");
    }

    @Test
    void 并发两次store生成不同文件名() throws IOException {
        Path allowedRoot = tempDir.resolve("data").toAbsolutePath();
        Path storeDir = allowedRoot.resolve("tool-output").toAbsolutePath();
        ToolOutputTempStore store = newStore(allowedRoot.toString(), storeDir.toString());

        TempFileRecord r1 = store.store("内容1", "httpGet");
        TempFileRecord r2 = store.store("内容2", "httpGet");
        assertNotEquals(r1.getAbsolutePath(), r2.getAbsolutePath(), "两次写入的文件名不应冲突");
    }

    @Test
    void 目录不可写时抛出IOException信号() throws IOException {
        Path allowedRoot = tempDir.resolve("data").toAbsolutePath();
        Files.createDirectories(allowedRoot);
        // 在 allowedRoot 下创建普通文件 blocker，使 blocker/sub 无法成为目录，createDirectories 失败
        Files.writeString(allowedRoot.resolve("blocker"), "x");

        ToolSanitizeProperties props = new ToolSanitizeProperties();
        props.setTempDir(allowedRoot.resolve("blocker").resolve("sub").toString());
        ToolOutputTempStore store = new ToolOutputTempStore(props, allowedRoot.toString());
        assertThrows(IOException.class, () -> store.store("内容", "httpGet"),
                "目录不可写时 store 应抛出 IOException 供管道降级");
    }
}
