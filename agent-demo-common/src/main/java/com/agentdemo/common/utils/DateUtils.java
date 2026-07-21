package com.agentdemo.common.utils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * 日期时间工具类
 * <p>
 * 业务含义：统一处理日期时间的格式化与解析，默认时区为 Asia/Shanghai。
 * 基于 java.time API，线程安全。
 * </p>
 */
public final class DateUtils {

    private DateUtils() {
        // 工具类禁止实例化
    }

    /**
     * 默认时区（中国标准时间）
     */
    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * 标准日期时间格式
     */
    public static final String PATTERN_DATETIME = "yyyy-MM-dd HH:mm:ss";

    /**
     * 标准日期格式
     */
    public static final String PATTERN_DATE = "yyyy-MM-dd";

    /**
     * 标准时间格式
     */
    public static final String PATTERN_TIME = "HH:mm:ss";

    /**
     * 获取当前时间（默认时区）
     *
     * @return 当前时间
     */
    public static LocalDateTime now() {
        return LocalDateTime.now(DEFAULT_ZONE);
    }

    /**
     * 格式化日期时间（标准格式 yyyy-MM-dd HH:mm:ss）
     *
     * @param dateTime 日期时间
     * @return 格式化字符串
     */
    public static String format(LocalDateTime dateTime) {
        return format(dateTime, PATTERN_DATETIME);
    }

    /**
     * 格式化日期时间（自定义格式）
     *
     * @param dateTime 日期时间
     * @param pattern  格式
     * @return 格式化字符串
     */
    public static String format(LocalDateTime dateTime, String pattern) {
        if (dateTime == null) {
            return null;
        }
        return DateTimeFormatter.ofPattern(pattern).format(dateTime);
    }

    /**
     * 解析日期时间（标准格式 yyyy-MM-dd HH:mm:ss）
     *
     * @param text 日期时间字符串
     * @return LocalDateTime
     */
    public static LocalDateTime parse(String text) {
        return parse(text, PATTERN_DATETIME);
    }

    /**
     * 解析日期时间（自定义格式）
     *
     * @param text    日期时间字符串
     * @param pattern 格式
     * @return LocalDateTime
     */
    public static LocalDateTime parse(String text, String pattern) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        return LocalDateTime.parse(text, DateTimeFormatter.ofPattern(pattern));
    }

    /**
     * 计算两个时间的毫秒差
     *
     * @param start 开始时间
     * @param end   结束时间
     * @return 毫秒差
     */
    public static long diffMillis(LocalDateTime start, LocalDateTime end) {
        return ChronoUnit.MILLIS.between(start, end);
    }
}
