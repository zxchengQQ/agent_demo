package com.agentdemo.tools.builtin;

import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 业务含义：提供只读文件能力，Agent 可读取本地文件内容。
 * 安全措施：只读（不提供写操作），路径限制（只能读取允许目录内文件），大小限制（1MB）。
 * </p>
 */
@Component
public class FileReadTool {

    private static final Logger log = LoggerFactory.getLogger(FileReadTool.class);

    /**
     * 最大文件大小（1MB，超过拒绝读取）
     */
    private static final long MAX_FILE_SIZE = 1024 * 1024;

    /**
     * 允许读取的根目录（通过配置注入，默认为当前目录下的 data/）
     */
    @Value("${agent.file.allowed-dir:./data}")
    private String allowedDir;

    /**
     * 读取指定路径文件内容
     * 业务含义：Agent 可调用此工具读取本地文件，路径必须在允许目录内
     *
     * @param path 文件相对路径（相对于允许目录）
     * @return 文件内容字符串
     * @throws BusinessException 路径越界、文件过大、读取失败时抛出
     */
    @Tool("读取指定路径的文件内容，参数 path 为相对路径（相对于允许目录），仅支持只读")
    public String readFile(String path) {
        try {
            Path resolvedPath = resolveAndValidate(path);
            log.info("读取文件: {}", resolvedPath);

            // 校验文件大小
            long size = Files.size(resolvedPath);
            if (size > MAX_FILE_SIZE) {
                throw new BusinessException(ErrorCode.TOOL_EXECUTION_FAILED,
                        "文件过大：" + size + " 字节，最大支持 " + MAX_FILE_SIZE + " 字节");
            }

            return Files.readString(resolvedPath, StandardCharsets.UTF_8);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.TOOL_EXECUTION_FAILED,
                    "文件读取失败: " + path, e);
        }
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

        Path allowedRoot = Paths.get(allowedDir).toAbsolutePath().normalize();
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
