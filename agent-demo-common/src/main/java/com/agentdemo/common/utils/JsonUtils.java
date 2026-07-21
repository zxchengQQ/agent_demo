package com.agentdemo.common.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.agentdemo.common.exception.BusinessException;
import com.agentdemo.common.exception.ErrorCode;

/**
 * JSON 工具类
 * <p>
 * 业务含义：基于 Jackson 的 JSON 序列化/反序列化工具，统一处理 JSON 转换异常。
 * 全部为静态方法，无实例状态，线程安全（ObjectMapper 本身线程安全）。
 * </p>
 */
public final class JsonUtils {

    private JsonUtils() {
        // 工具类禁止实例化
    }

    /**
     * 全局共享的 ObjectMapper（线程安全，配置一次复用）
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            // 注册 Java 8 时间模块
            .registerModule(new JavaTimeModule())
            // 序列化时忽略 null 字段
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
            // 反序列化时忽略未知字段
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            // 日期不序列化为时间戳
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /**
     * 对象序列化为 JSON 字符串
     *
     * @param obj 待序列化对象
     * @return JSON 字符串
     * @throws BusinessException 序列化失败
     */
    public static String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "JSON 序列化失败", e);
        }
    }

    /**
     * JSON 字符串反序列化为对象
     *
     * @param json  JSON 字符串
     * @param clazz 目标类型
     * @param <T>   泛型
     * @return 反序列化对象
     * @throws BusinessException 反序列化失败
     */
    public static <T> T fromJson(String json, Class<T> clazz) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "JSON 反序列化失败", e);
        }
    }

    /**
     * JSON 字符串反序列化为复杂类型（如 List、Map）
     *
     * @param json          JSON 字符串
     * @param typeReference 类型引用
     * @param <T>           泛型
     * @return 反序列化对象
     * @throws BusinessException 反序列化失败
     */
    public static <T> T fromJson(String json, TypeReference<T> typeReference) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, typeReference);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "JSON 反序列化失败", e);
        }
    }

    /**
     * 获取全局共享的 ObjectMapper（供外部扩展使用）
     *
     * @return ObjectMapper 实例
     */
    public static ObjectMapper getObjectMapper() {
        return OBJECT_MAPPER;
    }
}
