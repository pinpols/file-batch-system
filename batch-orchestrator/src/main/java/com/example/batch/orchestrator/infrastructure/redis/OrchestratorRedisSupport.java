package com.example.batch.orchestrator.infrastructure.redis;

import com.example.batch.common.redis.BatchRedisKeys;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Orchestrator Redis 操作工具类：封装常用 Redis 操作，统一序列化/反序列化并提供原子化操作支持。
 *
 * <p>主要能力：JSON 值的 get/set/delete（反序列化失败时自动删除脏数据）、
 * Hash 整体写入、滑动窗口计数器（{@code incrementWithinWindow}，首次写入时设置 TTL），
 * 以及执行 Lua 脚本（{@code evalLong}）。
 */
@Component
@RequiredArgsConstructor
public class OrchestratorRedisSupport {

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;

  public StringRedisTemplate redisTemplate() {
    return redisTemplate;
  }

  public <T> T getJson(String key, Class<T> type) {
    String value = redisTemplate.opsForValue().get(key);
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(value, type);
    } catch (JsonProcessingException exception) {
      redisTemplate.delete(key);
      throw new IllegalStateException("Failed to deserialize redis cache: " + key, exception);
    }
  }

  public void setJson(String key, Object value, Duration ttl) {
    try {
      String json = objectMapper.writeValueAsString(value);
      redisTemplate.opsForValue().set(key, json, ttl);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to serialize redis cache: " + key, exception);
    }
  }

  public void delete(String key) {
    redisTemplate.delete(key);
  }

  public void putHashAll(String key, Map<String, String> fields, Duration ttl) {
    redisTemplate.opsForHash().putAll(key, fields);
    redisTemplate.expire(key, ttl);
  }

  public Map<Object, Object> entries(String key) {
    return redisTemplate.opsForHash().entries(key);
  }

  public Long incrementWithinWindow(
      String tenantId, String action, long windowStartEpochSecond, Duration ttl) {
    String key = BatchRedisKeys.rateLimit(tenantId, action, windowStartEpochSecond);
    Long value = redisTemplate.opsForValue().increment(key);
    if (value != null && value == 1L) {
      redisTemplate.expire(key, ttl);
    }
    return value;
  }

  public Long evalLong(String script, String key, String... args) {
    return redisTemplate.execute(
        new DefaultRedisScript<>(script, Long.class), List.of(key), (Object[]) args);
  }
}
