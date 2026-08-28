package com.agentdemo.memory.shortterm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 记忆压缩配置属性
 * <p>
 * 业务含义：集中管理会话记忆滚动摘要压缩的开关。通过 application.yml 中
 * agent.memory-compression.* 前缀注入；enabled=false 时退化为 FIFO 窗口行为（现状，
 * 回滚通道）。
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.memory-compression")
public class MemoryCompressionProperties {

    /** 压缩总开关：false 时退化为 MessageWindowChatMemory FIFO（现状行为） */
    private boolean enabled = true;
}
