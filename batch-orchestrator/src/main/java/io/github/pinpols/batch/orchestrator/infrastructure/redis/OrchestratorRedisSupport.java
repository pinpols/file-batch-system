package io.github.pinpols.batch.orchestrator.infrastructure.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.logging.LogSanitizer;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.redis.BatchRedisKeys;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Orchestrator Redis 操作工具类：封装常用 Redis 操作，统一序列化/反序列化并提供原子化操作支持。
 *
 * <p>主要能力：JSON 值的 get/set/delete（反序列化失败时自动删除异常数据）、 Hash 整体写入， 以及执行 Lua 脚本（{@code evalLong} / {@code
 * evalList}）。
 *
 * <p>Cache-Aside 方法使用 best-effort 语义：Redis 连接类异常（{@link RedisConnectionFailureException} / {@link
 * RedisSystemException}）时读返回 null、写/删跳过，调用方回落 PG。Quota、Lua 等强语义操作不吞 Redis 异常。
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

  /** 按租户和配置类型读取失效版本；任一版本不可用时不得回填配置缓存。 */
  public String configInvalidationVersions(String tenantId, String type, String code) {
    String wildcardKey = BatchRedisKeys.configInvalidationKeyRevision(tenantId, type, "*");
    String exactKey = BatchRedisKeys.configInvalidationKeyRevision(tenantId, type, code);
    Long wildcardRevision = configRevision(wildcardKey);
    if (EmptyChecks.isNull(wildcardRevision)) {
      return null;
    }
    Long exactRevision = configRevision(exactKey);
    if (EmptyChecks.isNull(exactRevision)) {
      return null;
    }
    return wildcardRevision + ":" + exactRevision;
  }

  private Long configRevision(String key) {
    try {
      String value = redisTemplate.opsForValue().get(key);
      if (EmptyChecks.isNull(value)) {
        return 0L;
      }
      long revision = Long.parseLong(value);
      if (revision < 0) {
        throw new NumberFormatException("negative config invalidation revision");
      }
      return revision;
    } catch (RedisConnectionFailureException
        | RedisSystemException
        | QueryTimeoutException
        | NumberFormatException ex) {
      log.warn(
          "config invalidation revision unavailable: reason={}",
          SwallowedExceptionLogger.summary(ex));
      return null;
    }
  }

  /** 直接清理配置缓存时推进对应配置和全局版本。 */
  public void advanceConfigInvalidationRevision(String tenantId, String type, String code) {
    String key = BatchRedisKeys.configInvalidationKeyRevision(tenantId, type, code);
    String globalKey = BatchRedisKeys.configInvalidationGlobalRevision();
    cacheWrite(key, () -> {
      redisTemplate.opsForValue().increment(key);
      redisTemplate.opsForValue().increment(globalKey);
    });
  }

  public <T> T getJson(String key, Class<T> type) {
    String raw = getStringCache(key);
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(raw, type);
    } catch (JsonProcessingException exception) {
      log.warn(
          "Redis cache JSON is corrupt; evicting and falling back to DB: key={}",
          LogSanitizer.value(key));
      log.debug("Redis cache JSON deserialize failure: key={}", LogSanitizer.value(key), exception);
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

  /** 尽力写入缓存：Redis 不可用时跳过写入。 */
  public void setStringCache(String key, String value, Duration ttl) {
    cacheWrite(key, () -> redisTemplate.opsForValue().set(key, value, ttl));
  }

  /** 尽力清除缓存：Redis 不可用时跳过删除。 */
  public void evictCache(String key) {
    cacheWrite(key, () -> redisTemplate.delete(key));
  }

  private <T> T cacheRead(String key, Supplier<T> supplier) {
    try {
      return supplier.get();
    } catch (RedisConnectionFailureException | RedisSystemException | QueryTimeoutException ex) {
      log.warn(
          "Redis cache read unavailable; falling back to DB: key={}, cause={}",
          LogSanitizer.value(key),
          SwallowedExceptionLogger.summary(ex));
      log.debug("Redis cache read failure: key={}", LogSanitizer.value(key), ex);
      return null;
    }
  }

  private void cacheWrite(String key, Runnable action) {
    try {
      action.run();
    } catch (RedisConnectionFailureException | RedisSystemException | QueryTimeoutException ex) {
      log.debug(
          "Redis cache write/delete skipped: key={}, cause={}",
          LogSanitizer.value(key),
          SwallowedExceptionLogger.summary(ex));
    }
  }

  // R3-P0-9：HSET + EXPIRE 两次 round-trip 非原子；连接中断时 hash 无 TTL → stale 缓存永久。
  // 改用 HSET 全字段 + PEXPIRE 同 Lua 一次原子执行；额外加 try-catch 走 cacheWrite 的 fail-open 路径。
  private static final String LUA_HSET_EXPIRE = """
      for i = 1, #ARGV - 1, 2 do redis.call('HSET', KEYS[1], ARGV[i], ARGV[i+1]) end
      redis.call('PEXPIRE', KEYS[1], ARGV[#ARGV])
      return 1
      """.stripTrailing();

  public void putHashAll(String key, Map<String, String> fields, Duration ttl) {
    cacheWrite(key, () -> {
      if (fields == null || fields.isEmpty()) {
        return;
      }
      List<String> args = new ArrayList<>(fields.size() * 2 + 1);
      for (Map.Entry<String, String> e : fields.entrySet()) {
        args.add(e.getKey());
        args.add(e.getValue() == null ? "" : e.getValue());
      }
      long ttlMillis = ttl.toMillis();
      String ttlArgument = String.valueOf(ttlMillis);
      args.add(ttlArgument);
      redisTemplate.execute(
          new DefaultRedisScript<>(LUA_HSET_EXPIRE, Long.class), List.of(key), args.toArray());
    });
  }

  // R3-P2-8：entries 也走 best-effort 包装；Redis 不可达时返回空 map 让上游 fallback DB，
  // 不再让 governance 接口因 Redis 故障整条异常退出。
  public Map<Object, Object> entries(String key) {
    Map<Object, Object> result = cacheRead(key, () -> redisTemplate.opsForHash().entries(key));
    return result == null ? Map.of() : result;
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
