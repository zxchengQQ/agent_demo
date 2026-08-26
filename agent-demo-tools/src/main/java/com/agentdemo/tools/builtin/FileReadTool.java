package com.agentdemo.tools.builtin;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import com.agentdemo.tools.permission.DefaultToolPermission;
import com.agentdemo.tools.permission.ToolPermissionLevel;
import com.agentdemo.tools.sanitize.SanitizeContext;
import com.agentdemo.tools.sanitize.ToolOutputSanitizer;
import com.agentdemo.tools.sanitize.ToolOutputTempStore;
import com.agentdemo.tools.sanitize.ToolSanitizeProperties;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文件读取工具
 * <p>
 * 业务含义：提供只读文件能力，Agent 可读取本地文件内容；支持 offset/maxChars 可选参数
 * 分段读取（AC-T02）。路径限制（只能读取允许目录内文件，AC-S08）。
 * </p>
 * <p>
 * 行为变更（AC-T03）：原"1MB 以上拒绝读取"改为读取后经清洗管道③限长 + 临时文件指引，
 * 大文件不再直接失败。
 * </p>
 * <p>
 * 递归截断防护（AC-T02/M01）：路径位于工具产出临时目录内时，豁免二次清洗（内容已清洗过、
 * 声明只出现一次），仅按 offset/maxChars 分页返回并硬截断，不再生成嵌套临时文件。
 * </p>
 */
@Component
@DefaultToolPermission(ToolPermissionLevel.ASK)
public class FileReadTool {

    private static final Logger log = LoggerFactory.getLogger(FileReadTool.class);

    private final ToolOutputSanitizer sanitizer;
    private final ToolSanitizeProperties sanitizeProperties;
    private final Path allowedRoot;

    /**
     * 无参构造（测试/回退场景）：默认使用直通清洗器（不启用清洗），白名单根默认 ./data
     */
    public FileReadTool() {
        this(ToolOutputSanitizer.disabled(), new ToolSanitizeProperties(), "./data");
    }

    /**
     * 真实构造（Spring 注入）：清洗链路生效。@Autowired 明确指定 Spring 在多构造器下
     * 使用本构造注入依赖；allowedDir 经 @Value 注入 agent.file-allowed-dir 配置（AC-S08 白名单根）。
     */
    @Autowired
    public FileReadTool(ToolOutputSanitizer sanitizer, ToolSanitizeProperties sanitizeProperties,
                        @Value("${agent.file-allowed-dir:./data}") String allowedDir) {
        this.sanitizer = sanitizer;
        this.sanitizeProperties = sanitizeProperties;
        this.allowedRoot = Paths.get(allowedDir).toAbsolutePath().normalize();
    }

    /**
     * 读取指定路径文件内容（支持可选分页参数）
     * <p>
     * 业务含义：Agent 可调用此工具读取本地文件，路径必须在允许目录内。
     * offset/maxChars 为可选参数：缺省（null）时普通文件读取全文经清洗返回；
     * 读取工具产出临时文件（超长内容剩余部分）时按窗口分页返回（AC-T02）。
     * </p>
     *
     * @param path     文件相对路径（相对于允许目录）
     * @param offset   分页起始偏移（字符，0 起；null 表示从开头）
     * @param maxChars 本次最多返回字符数（null 表示全部剩余）
     * @return 文件内容字符串（临时文件回读为分页窗口 + 剩余量提示）
     * @throws BusinessException 路径越界、文件不存在、读取失败时抛出
     */
    @Tool("读取本地文件内容。"
            + "适用场景：需要查看本地文件（如配置文件、代码文件、数据文件）或分段查看超长工具结果的剩余内容时调用。"
            + "不适用场景：检索知识库内容用知识库检索工具。"
            + "参数 path 为相对路径（相对于允许目录 ./data）。"
            + "可选参数 offset（字符偏移，0 起）与 maxChars（本次最多返回字符数）用于分段读取；"
            + "缺省时读取全文。路径越界或文件不存在时返回错误。")
    public String readFile(String path, Integer offset, Integer maxChars) {
        try {
            Path resolvedPath = resolveAndValidate(path);
            log.info("读取文件: {}", resolvedPath);

            // 临时目录豁免（AC-T02/M01）：回读工具产出临时文件，不二次清洗、不嵌套临时文件
            if (isWithinTempDir(resolvedPath)) {
                return readTempSegment(resolvedPath, offset, maxChars);
            }

            // 普通文件：读取全文（移除 1MB 拒绝，AC-T03），统一经清洗管道（含超长截断 + 包裹声明）
            String content = Files.readString(resolvedPath, StandardCharsets.UTF_8);
            return sanitizer.sanitize(content, SanitizeContext.builder()
                    .toolName("readFile")
                    .sourceDesc("本地文件内容")
                    .htmlContent(false)
                    .build());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.TOOL_EXECUTION_FAILED,
                    "文件读取失败: " + path, e);
        }
    }

    /**
     * 临时文件分段读取（豁免分支）
     * <p>
     * 业务含义：临时文件头部为 2 行元信息，正文从第 3 行起。按 offset/maxChars 取窗口，
     * 尾部追加剩余量提示供模型继续分页。硬截断不落盘，终结递归。
     * </p>
     */
    private String readTempSegment(Path file, Integer offset, Integer maxChars) throws IOException {
        String full = Files.readString(file, StandardCharsets.UTF_8);
        // 跳过头部 META_LINES 行元信息，定位正文起点
        int bodyStart = 0;
        for (int i = 0; i < ToolOutputTempStore.META_LINES && bodyStart < full.length(); i++) {
            int idx = full.indexOf('\n', bodyStart);
            if (idx < 0) {
                bodyStart = full.length();
                break;
            }
            bodyStart = idx + 1;
        }
        String body = full.substring(bodyStart);
        int start = (offset != null && offset >= 0) ? Math.min(offset, body.length()) : 0;
        int length = (maxChars != null && maxChars > 0) ? maxChars : body.length();
        int end = Math.min(start + length, body.length());
        String segment = body.substring(start, end);
        int remaining = body.length() - end;
        if (remaining > 0) {
            return segment + "\n[临时文件剩余 " + remaining + " 字符未展示，"
                    + "如需继续查看请再次调用 readFile，offset=" + end + ", maxChars=" + length + "。]";
        }
        return segment;
    }

    /**
     * 判断路径是否位于工具产出临时目录内（豁免分支判定）
     */
    private boolean isWithinTempDir(Path resolvedPath) {
        Path tempRoot = Paths.get(sanitizeProperties.getTempDir()).toAbsolutePath().normalize();
        return resolvedPath.startsWith(tempRoot);
    }

    /**
     * 解析并校验路径
     * 业务含义：路径安全校验，防止路径遍历攻击（如 ../etc/passwd）
     *
     * @param path 用户输入的相对路径
     * @return 规范化后的绝对路径
     * @throws BusinessException 路径越界时抛出
     */
    private Path resolveAndValidate(String path) throws IOException {
        if (path == null || path.isEmpty()) {
            throw new BusinessException(ErrorCode.TOOL_PARAM_INVALID, "文件路径不能为空");
        }

        Path resolved = allowedRoot.resolve(path).normalize();

        // 业务含义：校验解析后的路径是否仍在允许目录内，防止路径遍历攻击
        if (!resolved.startsWith(allowedRoot)) {
            throw new BusinessException(ErrorCode.TOOL_PARAM_INVALID,
                    "路径越界，禁止访问允许目录外的文件: " + path);
        }

        if (!Files.exists(resolved)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文件不存在: " + path);
        }

        if (!Files.isRegularFile(resolved)) {
            throw new BusinessException(ErrorCode.TOOL_PARAM_INVALID, "不是有效文件: " + path);
        }

        return resolved;
    }
}
