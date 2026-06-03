package com.example.batch.common.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON 序列化/反序列化工具类。
 *
 * <p>内部维护两个单例 ObjectMapper：
 *
 * <ul>
 *   <li>{@link #MAPPER} — 宽容模式（{@code FAIL_ON_UNKNOWN_PROPERTIES=false}），用于跨版本 payload /
 *       事件、可扩展字段场景，旧消费者不会因上游加字段而 crash。
 *   <li>{@link #STRICT_MAPPER} — 严格模式（{@code FAIL_ON_UNKNOWN_PROPERTIES=true}），用于 明确契约的
 *       DTO、配置文件、受信任的内部序列化；未知字段立即抛异常，防止上游打错 字段名被静默忽略。
 * </ul>
 *
 * <p>序列化或反序列化失败时抛出 {@link IllegalArgumentException}，调用方无需处理受检异常。
 *
 * <p><b>S-1.6 选型建议</b>：新增 DTO 默认走 {@link #fromJsonStrict}；只在对外接收未知 payload 或跨版本消息场景才用 {@link
 * #fromJson}。
 */
public final class JsonUtils {

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .findAndRegisterModules()
          // 宽容 mapper 默认不开 unknown properties 检查，保持与既有调用兼容
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private static final ObjectMapper STRICT_MAPPER =
      new ObjectMapper()
          .findAndRegisterModules()
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

  private JsonUtils() {}

  public static String toJson(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("Failed to serialize JSON", ex);
    }
  }

  /** 宽容反序列化：未知字段被静默忽略。适合跨版本 / 可扩展 payload。 */
  public static <T> T fromJson(String json, Class<T> type) {
    try {
      return MAPPER.readValue(json, type);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("Failed to parse JSON", ex);
    }
  }

  /**
   * 宽容反序列化（泛型版）：用于 {@code List<Foo>} / {@code Map<String, Foo>} 之类参数化类型。 调用方传入 {@link
   * TypeReference}。
   */
  public static <T> T fromJson(String json, TypeReference<T> typeRef) {
    try {
      return MAPPER.readValue(json, typeRef);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("Failed to parse JSON", ex);
    }
  }

  /**
   * 严格反序列化：未知字段直接抛 {@link IllegalArgumentException}。S-1.6：适合契约 DTO / 配置文件 /
   * 受信任内部序列化，能在字段重命名或拼写错误时立即暴露。
   */
  public static <T> T fromJsonStrict(String json, Class<T> type) {
    try {
      return STRICT_MAPPER.readValue(json, type);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("Failed to parse JSON (strict)", ex);
    }
  }

  /**
   * POJO → Map(LinkedHashMap)。给 DomainEventPublisher 之类的 payload 抽象用,避免 toJson + fromJson 二次序列化。
   */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> toMap(Object value) {
    if (value == null) {
      return Map.of();
    }
    return MAPPER.convertValue(value, LinkedHashMap.class);
  }
}
