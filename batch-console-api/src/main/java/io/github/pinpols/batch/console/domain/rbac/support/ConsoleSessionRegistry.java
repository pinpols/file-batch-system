package io.github.pinpols.batch.console.domain.rbac.support;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.config.ConsoleSecurityProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;

/**
 * 控制台单会话注册表：按 {@code (tenantId, username)} 在 Redis 记录当前有效的 session 版本号， 实现"新登录踢旧会话"。
 *
 * <p>关键语义：
 *
 * <ul>
 *   <li><b>开关</b>：{@code singleSessionEnabled=false} 时所有方法直通（nextSessionVersion 返回 0，
 *       isCurrentSession 恒 true）——生产可一键关闭，便于故障回退。
 *   <li><b>递增即生效</b>：{@link #nextSessionVersion} 每次登录 {@code INCR}，使现有 JWT 的 {@code
 *       session_version} 落后即被 {@link ConsoleJwtService#authenticate} 判为 UNAUTHORIZED。
 *   <li><b>invalidateSession</b>：删 key 让所有该用户 JWT 在下次请求失效（管理员强制登出 / 密码重置场景）。
 *   <li><b>Key 规范化</b>：username / tenantId 统一 {@code trim + toLowerCase}，防止大小写或首尾空格绕过会话隔离。
 *   <li><b>TTL 30 天</b>（或配置的 {@code sessionStateTtl}）：Redis key 过期防内存泄漏；长期不登录的用户 下次登录时 {@code
 *       currentSessionVersion} 返回 0，natural 触发新 session 建立。
 * </ul>
 *
 * <p><b>Redis 降级策略</b>（本进程内 Caffeine L1 + fail-open 回退）：
 *
 * <ol>
 *   <li>写路径（nextSessionVersion / invalidateSession）：Redis 成功后同步写 Caffeine；Redis 异常时 继续更新 Caffeine
 *       并返回本地值，避免登录被阻断。跨 Pod 单会话语义短暂失效（运维 Grafana 可见）。
 *   <li>读路径（currentSessionVersion / isCurrentSession）：优先 Redis；Redis 异常时读 Caffeine 回退； Caffeine
 *       也缺失时 {@code isCurrentSession} 返回 true（fail-open），让用户继续操作。
 * </ol>
 *
 * <p>权衡：短暂 Redis 抖动 vs "踢旧会话"严格性。生产上 Redis 抖动 > 踢旧会话被绕过几分钟的风险可控。 想关降级逻辑走严格模式：设 {@code
 * batch.console.security.sessionFailOpen=false}（在各调用处判断即可； 本类保持 fail-open 作为默认安全网）。
 */
@Slf4j
@Service
public class ConsoleSessionRegistry {

  private static final String KEY_PREFIX = "batch:console:auth:session:";

  private final StringRedisTemplate redisTemplate;
  private final ConsoleSecurityProperties securityProperties;

  /** 进程内会话版本镜像，Redis 抖动时回退。TTL 与 Redis 一致，防止内存累积。 */
  private final Cache<String, Long> localMirror;

  public ConsoleSessionRegistry(
      StringRedisTemplate redisTemplate,
      ConsoleSecurityProperties securityProperties,
      ObjectProvider<MeterRegistry> meterRegistryProvider) {
    this.redisTemplate = redisTemplate;
    this.securityProperties = securityProperties;
    // R-4.7：显式开 recordStats 让 Caffeine 的命中率 / 驱逐计数可观测；
    // expireAfterWrite + maximumSize 原有语义保留，运维可通过 actuator /
    // batch.console.session.* 指标判断容量是否需要调整。
    this.localMirror =
        Caffeine.newBuilder()
            .expireAfterWrite(resolveTtl(securityProperties))
            .maximumSize(100_000)
            .recordStats()
            .build();
    registerCacheMetrics(meterRegistryProvider);
  }

  private void registerCacheMetrics(ObjectProvider<MeterRegistry> meterRegistryProvider) {
    MeterRegistry registry = meterRegistryProvider.getIfAvailable();
    if (registry == null) {
      return;
    }
    // 基础计数 gauge；更完整的 CaffeineStats / tagged metrics 可后续接 micrometer-caffeine
    registry.gauge("batch.console.session.cache.size", localMirror, Cache::estimatedSize);
    registry.gauge("batch.console.session.cache.hit_count", localMirror, c -> c.stats().hitCount());
    registry.gauge(
        "batch.console.session.cache.miss_count", localMirror, c -> c.stats().missCount());
    registry.gauge(
        "batch.console.session.cache.eviction_count", localMirror, c -> c.stats().evictionCount());
  }

  public long nextSessionVersion(String username, String tenantId) {
    if (!securityProperties.isSingleSessionEnabled()) {
      return 0L;
    }
    String key = key(username, tenantId);
    try {
      Long version = redisTemplate.opsForValue().increment(key);
      // C-2.9: INCR 返回权威新值后立即同步到 Caffeine，再做 expire。
      // 之前顺序是 INCR → expire → put mirror，若 expire 抛异常会走 catch 分支
      // 用本地旧值 +1（可能落后 Redis 真实值），导致单会话判断错乱。
      long v = version == null ? 1L : version;
      localMirror.put(key, v);
      try {
        redisTemplate.expire(key, sessionStateTtl());
      } catch (DataAccessException expireEx) {
        // TTL 刷失败不影响版本号正确性，仅可能让 key 提前 / 延后到期。记 warn 不抛。
        log.warn(
            "Redis expire() failed for session key (value already incremented): {}",
            expireEx.getMessage());
      }
      return v;
    } catch (DataAccessException ex) {
      // Redis INCR 不可达：本地自增并返回，保证登录不被阻断。
      // 注意：跨 Pod 单会话语义在此降级期失效，Grafana 报警应能发现。
      Long current = localMirror.getIfPresent(key);
      long v = current == null ? 1L : current + 1L;
      localMirror.put(key, v);
      log.warn(
          "Redis unavailable during nextSessionVersion; falling back to local mirror: {}",
          ex.getMessage());
      return v;
    }
  }

  public long currentSessionVersion(String username, String tenantId) {
    if (!securityProperties.isSingleSessionEnabled()) {
      return 0L;
    }
    String key = key(username, tenantId);
    try {
      ValueOperations<String, String> ops = redisTemplate.opsForValue();
      String raw = ops.get(key);
      if (!Texts.hasText(raw)) {
        return 0L;
      }
      try {
        long v = Long.parseLong(raw);
        localMirror.put(key, v);
        return v;
      } catch (NumberFormatException ignored) {
        SwallowedExceptionLogger.info(
            ConsoleSessionRegistry.class, "catch:NumberFormatException", ignored);

        return 0L;
      }
    } catch (DataAccessException ex) {
      Long cached = localMirror.getIfPresent(key);
      log.warn(
          "Redis unavailable during currentSessionVersion; local mirror={}, cause={}",
          cached,
          ex.getMessage());
      return cached == null ? 0L : cached;
    }
  }

  /** 使指定用户的当前会话立即失效（删除 Redis key，已有 JWT 在下次请求时失败）。 */
  public void invalidateSession(String username, String tenantId) {
    String key = key(username, tenantId);
    try {
      redisTemplate.delete(key);
    } catch (DataAccessException ex) {
      log.warn(
          "Redis unavailable during invalidateSession; local-only eviction: {}", ex.getMessage());
    }
    localMirror.invalidate(key);
  }

  public boolean isCurrentSession(String username, String tenantId, long sessionVersion) {
    if (!securityProperties.isSingleSessionEnabled()) {
      return true;
    }
    try {
      long current = currentSessionVersion(username, tenantId);
      return current > 0L && current == sessionVersion;
    } catch (DataAccessException ex) {
      // 非预期场景（currentSessionVersion 自身已回退），额外 fail-open
      log.warn("Unexpected Redis failure in isCurrentSession; fail-open: {}", ex.getMessage());
      return true;
    }
  }

  private Duration sessionStateTtl() {
    return resolveTtl(securityProperties);
  }

  private static Duration resolveTtl(ConsoleSecurityProperties props) {
    Duration ttl = props == null ? null : props.getSessionStateTtl();
    return ttl == null || ttl.isNegative() || ttl.isZero() ? Duration.ofDays(30) : ttl;
  }

  private String key(String username, String tenantId) {
    return KEY_PREFIX + normalize(tenantId) + ":" + normalize(username);
  }

  private String normalize(String value) {
    return value == null ? "unknown" : value.trim().toLowerCase(Locale.ROOT);
  }
}
