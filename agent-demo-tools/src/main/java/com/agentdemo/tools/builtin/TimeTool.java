package com.agentdemo.tools.builtin;

import com.agentdemo.common.utils.DateUtils;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 时间查询工具
 * <p>
 * 业务含义：提供当前时间、日期、时区查询能力，Agent 可自主决定何时调用。
 * 基于 java.time API，默认时区 Asia/Shanghai。
 * </p>
 */
@Component
public class TimeTool {

    /**
     * 获取当前时间（默认时区 Asia/Shanghai）
     *
     * @return 格式化的当前时间字符串
     */
    @Tool("获取当前时间，返回标准格式 yyyy-MM-dd HH:mm:ss")
    public String getCurrentTime() {
        LocalDateTime now = DateUtils.now();
        return "当前时间：" + DateUtils.format(now);
    }

    /**
     * 获取指定时区的当前时间
     *
     * @param zoneId 时区 ID，如 "Asia/Shanghai"、"America/New_York"、"UTC"
     * @return 指定时区的当前时间字符串
     */
    @Tool("获取指定时区的当前时间，参数 zoneId 为时区 ID，如 Asia/Shanghai、America/New_York、UTC")
    public String getCurrentTimeByZone(String zoneId) {
        try {
            ZoneId zone = ZoneId.of(zoneId);
            LocalDateTime now = LocalDateTime.now(zone);
            return zoneId + " 当前时间：" + now.format(DateTimeFormatter.ofPattern(DateUtils.PATTERN_DATETIME));
        } catch (Exception e) {
            return "无效的时区 ID：" + zoneId + "，示例：Asia/Shanghai、America/New_York、UTC";
        }
    }

    /**
     * 获取当前日期
     *
     * @return 格式化的当前日期字符串
     */
    @Tool("获取当前日期，返回格式 yyyy-MM-dd")
    public String getCurrentDate() {
        LocalDateTime now = DateUtils.now();
        return "当前日期：" + now.format(DateTimeFormatter.ofPattern(DateUtils.PATTERN_DATE));
    }
}
