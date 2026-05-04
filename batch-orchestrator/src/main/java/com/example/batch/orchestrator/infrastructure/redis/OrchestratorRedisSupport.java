package com.example.batch.orchestrator.infrastructure.redis;

import com.example.batch.common.redis.BatchRedisKeys;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Orchestrator Redis 操作工具类：封装常用 Redis 操作，统一序列化/反序列化并提供原子化操作支持。
 *
 * <p>主要能力：JSON 值的 get/set/delete（反序列化失败时自动删除脏数据）、 Hash 整体写入、滑动窗口计数器（{@code
 * incrementWithinWindow}，首次写入时设置 TTL）， 以及执行 Lua 脚本（{@code evalLong}）。
 *
 * <p>JSON 缓存读写与 Orchestrator 配置 Cache-Aside 一致：Redis 连接类异常（{@link RedisConnectionFailureException}
 * / {@link RedisSystemException}）时对 {@link #getJson}/{@link #setJson}/{@link #delete}
 * fail-open——读返回 null、写/删静默跳过， 调用方回落 PG（见 {@link WorkerRegistryCache} 同类策略）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrchestratorRedisSupport {

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;

  public StringRedisTemplate redisTemplate() {
    return redisTemplate;
  }

  public <T> T getJson(String key, Class<T> type) {
    String raw =
        callRedisOrNull(key, "read (fail-open to DB)", () -> redisTemplate.opsForValue().get(key));
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(raw, type);
    } catch (JsonProcessingException exception) {
      deleteIgnoringRedisFailure(key, "Redis unavailable while deleting corrupt cache key {}: {}");
      throw new IllegalStateException("Failed to deserialize redis cache: " + key, exception);
    }
  }

  public void setJson(String key, Object value, Duration ttl) {
    final String json;
    try {
      json = objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to serialize redis cache: " + key, exception);
    }
    callRedisOrVoid(key, "write (skipped)", () -> redisTemplate.opsForValue().set(key, json, ttl));
  }

  public void delete(String key) {
    deleteIgnoringRedisFailure(
        key, "Redis unavailable for orchestrator cache delete (skipped): key={}, cause={}");
  }

  /** Redis 连接类异常时记录日志并返回 null；正常返回 supplier 结果（可为 null，表示 miss）。 */
  private <T> T callRedisOrNull(String key, String phase, Supplier<T> supplier) {
    try {
      return supplier.get();
    } catch (RedisConnectionFailureException | RedisSystemException ex) {
      logRedisFailure(key, phase, ex);
      return null;
    }
  }

  /** Redis 连接类异常时记录日志并吞掉；成功执行 runnable。 */
  private void callRedisOrVoid(String key, String phase, Runnable action) {
    try {
      action.run();
    } catch (RedisConnectionFailureException | RedisSystemException ex) {
      logRedisFailure(key, phase, ex);
    }
  }

  private void logRedisFailure(String key, String phase, Exception ex) {
    log.warn(
        "Redis unavailable for orchestrator cache {}: key={}, cause={}",
        phase,
        key,
        ex.getMessage());
    log.debug("Redis orchestrator cache failure [{}]: key={}", phase, key, ex);
  }

  private void deleteIgnoringRedisFailure(String key, String debugFormat) {
    try {
      redisTemplate.delete(key);
    } catch (RedisConnectionFailureException | RedisSystemException ex) {
      log.debug(debugFormat, key, ex.getMessage());
    }
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

  /**
   * 执行 Lua 脚本并返回 List 结果。元素类型依赖 Lua return 的形态：number → Long，string → String。 调用方按需 toString 后
   * parseLong / 直接用。Quota 状态服务等需要原子返回多字段的场景使用。
   */
  @SuppressWarnings("unchecked")
  public List<Object> evalList(String script, String key, String... args) {
    DefaultRedisScript<List> redisScript = new DefaultRedisScript<>(script, List.class);
    return redisTemplate.execute(redisScript, List.of(key), (Object[]) args);
  }
}
