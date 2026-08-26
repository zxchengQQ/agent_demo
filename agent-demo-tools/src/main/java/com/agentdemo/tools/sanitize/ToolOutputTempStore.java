package com.agentdemo.tools.sanitize;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 超长工具产出临时文件存储
 * <p>
 * 业务含义：工具产出超过字数上限时，将清洗后的完整内容落盘为临时文件，
 * 生成相对白名单根的可读路径（AC-T01）。Agent 通过 readFile 工具分段回读剩余内容（AC-T02）。
 * </p>
 * <p>
 * 约束：临时目录必须位于 agent.file.allowed-dir（默认 ./data）内，
 * 否则 relativePath 不在 FileReadTool 白名单内，模型无法回读。
 * </p>
 */
@Slf4j
@Component
public class ToolOutputTempStore {

    /** 文件头部元信息行数（第 1 行 SOURCE、第 2 行 ORIGINAL_LENGTH） */
    public static final int META_LINES = 2;

    private final ToolSanitizeProperties properties;
    private final Path allowedRoot;
    private final Path tempDir;

    public ToolOutputTempStore(ToolSanitizeProperties properties,
                               @Value("${agent.file-allowed-dir:./data}") String allowedDir) {
        this.properties = properties;
        this.allowedRoot = Paths.get(allowedDir).toAbsolutePath().normalize();
        this.tempDir = Paths.get(properties.getTempDir()).toAbsolutePath().normalize();
    }

    /**
     * 将超长内容落盘为临时文件
     *
     * @param content  清洗后的完整内容（前缀 + 溢出部分）
     * @param toolName 来源工具名（用于文件名与元信息）
     * @return 临时文件元信息
     * @throws IOException 目录不可写或写入失败时抛出（供管道降级纯截断，AC-E01）
     */
    public TempFileRecord store(String content, String toolName) throws IOException {
        // 业务含义：确保临时目录存在；写入前机会式清理超期文件（无调度器，AC 简单过期策略）
        Files.createDirectories(tempDir);
        cleanupIfNeeded();

        String fileName = sanitizeFileName(toolName) + "_"
                + System.currentTimeMillis() + "_"
                + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000000))
                + ".txt";
        Path file = tempDir.resolve(fileName);
        String header = "# SOURCE=" + toolName + "\n"
                + "# ORIGINAL_LENGTH=" + content.length() + "\n";
        Files.writeString(file, header + content, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE_NEW);

        SanitizeLogs.warn(toolName, "TEMP_FILE", "TEMP_FILE", "file=" + file + ", length=" + content.length());
        return TempFileRecord.builder()
                .relativePath(toReadableRelativePath(file))
                .absolutePath(file.toString())
                .originalLength(content.length())
                .keptLength(0)
                .build();
    }

    /**
     * 将绝对路径转换为相对白名单根的可读路径（供截断提示与 readFile 回读）
     *
     * @param absFile 临时文件绝对路径
     * @return 相对路径（分隔符统一为 /）
     */
    public String toReadableRelativePath(Path absFile) {
        return allowedRoot.relativize(absFile).toString().replace('\\', '/');
    }

    /**
     * 解析并校验 readFile 传入的相对路径位于临时目录内
     * <p>
     * 业务含义：FileReadTool 读取临时文件前的豁免判定：路径必须落在临时目录内，
     * 防止构造相似前缀的普通文件绕过豁免（AC-T02 豁免仅针对真正的临时文件）。
     * </p>
     *
     * @param relativePath 相对白名单根的可读路径
     * @return 校验通过后的绝对路径
     * @throws BusinessException 路径为空或越出临时目录时抛出
     */
    public Path resolveAndValidate(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            throw new BusinessException(ErrorCode.TOOL_PARAM_INVALID, "临时文件路径不能为空");
        }
        Path abs = allowedRoot.resolve(relativePath).normalize();
        // 业务含义：严格校验解析后的绝对路径必须位于临时目录内（AC-T02 豁免边界）
        if (!abs.startsWith(tempDir)) {
            throw new BusinessException(ErrorCode.TOOL_PARAM_INVALID,
                    "路径不在临时文件目录内: " + relativePath);
        }
        return abs;
    }

    /**
     * 读取临时文件头部的原始长度元信息
     *
     * @param file 临时文件绝对路径
     * @return 原始总长（清洗后全文长度）
     * @throws IOException 读取失败时抛出
     */
    public int readOriginalLength(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.size() < META_LINES) {
            return 0;
        }
        String line = lines.get(1);
        if (line.startsWith("# ORIGINAL_LENGTH=")) {
            return Integer.parseInt(line.substring("# ORIGINAL_LENGTH=".length()).trim());
        }
        return 0;
    }

    /**
     * 机会式清理：删除修改时间早于保留时长的文件（写入时顺带执行，不引入调度器）
     */
    private void cleanupIfNeeded() {
        if (!Files.isDirectory(tempDir)) {
            return;
        }
        long cutoff = System.currentTimeMillis() - properties.getTempRetentionHours() * 3600_000L;
        try (var stream = Files.list(tempDir)) {
            stream.filter(p -> {
                try {
                    return Files.isRegularFile(p) && Files.getLastModifiedTime(p).toMillis() < cutoff;
                } catch (IOException e) {
                    return false;
                }
            }).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    // 清理失败仅记 WARN，不影响写入主流程
                    log.warn("[tool-sanitize] 临时文件清理失败: {}", p, e);
                }
            });
        } catch (IOException e) {
            log.warn("[tool-sanitize] 临时目录扫描失败: {}", tempDir, e);
        }
    }

    /**
     * 文件名安全化：替换工具名中的非法路径字符，防止路径注入
     */
    private String sanitizeFileName(String toolName) {
        return toolName.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
