package com.example.batch.orchestrator.infrastructure.redis;

import com.example.batch.common.redis.BatchRedisKeys;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
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
 * <p>Cache-Aside 方法使用 best-effort 语义：Redis 连接类异常（{@link RedisConnectionFailureException} / {@link
 * RedisSystemException}）时读返回 null、写/删跳过，调用方回落 PG。限流、Quota、Lua 等强语义操作不吞 Redis 异常。
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
    String raw = getStringCache(key);
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(raw, type);
    } catch (JsonProcessingException exception) {
      log.warn("Redis cache JSON is corrupt; evicting and falling back to DB: key={}", key);
      log.debug("Redis cache JSON deserialize failure: key={}", key, exception);
      evictCache(key);
      return null;
    }
  }

  public void setJson(String key, Object value, Duration ttl) {
    final String json;
    try {
      json = objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to serialize redis cache: " + key, exception);
    }
    setStringCache(key, json, ttl);
  }

  public void delete(String key) {
    evictCache(key);
  }

  /** Best-effort cache read: Redis unavailable means cache miss. */
  public String getStringCache(String key) {
    return cacheRead(key, () -> redisTemplate.opsForValue().get(key));
  }

  /** Best-effort cache write: Redis unavailable skips the write. */
  public void setStringCache(String key, String value, Duration ttl) {
    cacheWrite(key, () -> redisTemplate.opsForValue().set(key, value, ttl));
  }

  /** Best-effort cache eviction: Redis unavailable skips the delete. */
  public void evictCache(String key) {
    cacheWrite(key, () -> redisTemplate.delete(key));
  }

  private <T> T cacheRead(String key, Supplier<T> supplier) {
    try {
      return supplier.get();
    } catch (RedisConnectionFailureException | RedisSystemException ex) {
      log.warn(
          "Redis cache read unavailable; falling back to DB: key={}, cause={}",
          key,
          ex.getMessage());
      log.debug("Redis cache read failure: key={}", key, ex);
      return null;
    }
  }

  private void cacheWrite(String key, Runnable action) {
    try {
      action.run();
    } catch (RedisConnectionFailureException | RedisSystemException ex) {
      log.debug("Redis cache write/delete skipped: key={}, cause={}", key, ex.getMessage());
    }
  }

  // R3-P0-9：HSET + EXPIRE 两次 round-trip 非原子；连接中断时 hash 无 TTL → stale 缓存永久。
  // 改用 HSET 全字段 + PEXPIRE 同 Lua 一次原子执行；额外加 try-catch 走 cacheWrite 的 fail-open 路径。
  private static final String LUA_HSET_EXPIRE =
      "for i = 1, #ARGV - 1, 2 do redis.call('HSET', KEYS[1], ARGV[i], ARGV[i+1]) end\n"
          + "redis.call('PEXPIRE', KEYS[1], ARGV[#ARGV])\n"
          + "return 1";

  public void putHashAll(String key, Map<String, String> fields, Duration ttl) {
    cacheWrite(
        key,
        () -> {
          if (fields == null || fields.isEmpty()) {
            return;
          }
          List<String> args = new ArrayList<>(fields.size() * 2 + 1);
          for (Map.Entry<String, String> e : fields.entrySet()) {
            args.add(e.getKey());
            args.add(e.getValue() == null ? "" : e.getValue());
          }
          args.add(String.valueOf(ttl.toMillis()));
          redisTemplate.execute(
              new DefaultRedisScript<>(LUA_HSET_EXPIRE, Long.class), List.of(key), args.toArray());
        });
  }

  // R3-P2-8：entries 也走 best-effort 包装；Redis 不可达时返回空 map 让上游 fallback DB，
  // 不再让 governance 接口因 Redis 故障整条挂掉。
  public Map<Object, Object> entries(String key) {
    Map<Object, Object> result = cacheRead(key, () -> redisTemplate.opsForHash().entries(key));
    return result == null ? Map.of() : result;
  }

  // R3-P0-10：INCR + EXPIRE 非原子；首次 INCR 成功后 expire 失败 → counter 永不过期，租户被永久 rate-limit。
  // 改 Lua：INCR 返回 1 时立即 PEXPIRE，单次往返保证原子。
  private static final String LUA_INCR_EXPIRE =
      "local v = redis.call('INCR', KEYS[1])\n"
          + "if v == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end\n"
          + "return v";

  public Long incrementWithinWindow(
      String tenantId, String action, long windowStartEpochSecond, Duration ttl) {
    String key = BatchRedisKeys.rateLimit(tenantId, action, windowStartEpochSecond);
    try {
      return redisTemplate.execute(
          new DefaultRedisScript<>(LUA_INCR_EXPIRE, Long.class),
          List.of(key),
          String.valueOf(ttl.toMillis()));
    } catch (RedisConnectionFailureException | RedisSystemException ex) {
      // fail-open：Redis 不可达时返回 null，调用方按 "未命中限流" 处理；与原 cacheWrite 一致语义
      log.warn("Redis rate-limit unavailable; fail-open: key={}, cause={}", key, ex.getMessage());
      return null;
    }
  }

  public Long evalLong(String script, String key, String... args) {
    return redisTemplate.execute(
        new DefaultRedisScript<>(script, Long.class), List.of(key), (Object[]) args);
  }

  /**
   * 执行 Lua 脚本并返回 List 结果。元素类型依赖 Lua return 的形态：number → Long，string → String。 调用方按需 toString 后
   * parseLong / 直接用。Quota 状态服务等需要原子返回多字段的场景使用。
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  public List<Object> evalList(String script, String key, String... args) {
    DefaultRedisScript<List> redisScript = new DefaultRedisScript<>(script, List.class);
    return redisTemplate.execute(redisScript, List.of(key), (Object[]) args);
  }
}
