package com.agentdemo.tools.sanitize;

import lombok.Builder;
import lombok.Data;

/**
 * 临时文件元信息
 * <p>
 * 业务含义：记录超长工具产出溢出部分落盘后的定位信息（AC-T01）。
 * relativePath 为相对 FileReadTool 白名单根（agent.file-allowed-dir）的可读路径，
 * Agent 可直接作为 readFile 的 path 参数分段回读剩余内容（AC-T02）。
 * </p>
 */
@Data
@Builder
public class TempFileRecord {

    /** 相对白名单根的可读路径（如 tool-output/httpGet_xxx.txt），供模型 readFile 使用 */
    private final String relativePath;

    /** 文件绝对路径 */
    private final String absolutePath;

    /** 写入时清洗后全文长度（原始总长） */
    private final int originalLength;

    /** 进入上下文的前缀长度（截断位置），由管道③设置 */
    private final int keptLength;
}
