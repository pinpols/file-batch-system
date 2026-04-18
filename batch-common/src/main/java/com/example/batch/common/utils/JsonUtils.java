package com.example.batch.common.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON 序列化/反序列化工具类。
 * 内部维护单例 {@link com.fasterxml.jackson.databind.ObjectMapper}，自动注册所有可用模块。
 * 序列化或反序列化失败时抛出 {@link IllegalArgumentException}，调用方无需处理受检异常。
 */
public final class JsonUtils {

  private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

  private JsonUtils() {}

  public static String toJson(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("Failed to serialize JSON", ex);
    }
  }

  public static <T> T fromJson(String json, Class<T> type) {
    try {
      return MAPPER.readValue(json, type);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("Failed to parse JSON", ex);
    }
  }
}
