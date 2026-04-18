package com.example.batch.console.support;

import com.example.batch.console.config.ConsoleSecurityProperties;
import java.time.Duration;
import java.util.Locale;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 控制台单会话注册表：按 {@code (tenantId, username)} 在 Redis 记录当前有效的 session 版本号，
 * 实现"新登录踢旧会话"。
 *
 * <p>关键语义：
 *
 * <ul>
 *   <li><b>开关</b>：{@code singleSessionEnabled=false} 时所有方法直通（nextSessionVersion 返回 0，
 *       isCurrentSession 恒 true）——生产可一键关闭，便于故障回退。
 *   <li><b>递增即生效</b>：{@link #nextSessionVersion} 每次登录 {@code INCR}，使现有 JWT 的
 *       {@code session_version} 落后即被 {@link ConsoleJwtService#authenticate} 判为 UNAUTHORIZED。
 *   <li><b>invalidateSession</b>：删 key 让所有该用户 JWT 在下次请求失效（管理员强制登出 / 密码重置场景）。
 *   <li><b>Key 规范化</b>：username / tenantId 统一 {@code trim + toLowerCase}，防止大小写或首尾空格绕过会话隔离。
 *   <li><b>TTL 30 天</b>（或配置的 {@code sessionStateTtl}）：Redis key 过期防内存泄漏；长期不登录的用户
 *       下次登录时 {@code currentSessionVersion} 返回 0，natural 触发新 session 建立。
 * </ul>
 */
@Service
public class ConsoleSessionRegistry {

  private static final String KEY_PREFIX = "batch:console:auth:session:";

  private final StringRedisTemplate redisTemplate;
  private final ConsoleSecurityProperties securityProperties;

  public ConsoleSessionRegistry(
      StringRedisTemplate redisTemplate, ConsoleSecurityProperties securityProperties) {
    this.redisTemplate = redisTemplate;
    this.securityProperties = securityProperties;
  }

  public long nextSessionVersion(String username, String tenantId) {
    if (!securityProperties.isSingleSessionEnabled()) {
      return 0L;
    }
    String key = key(username, tenantId);
    Long version = redisTemplate.opsForValue().increment(key);
    redisTemplate.expire(key, sessionStateTtl());
    return version == null ? 1L : version;
  }

  public long currentSessionVersion(String username, String tenantId) {
    if (!securityProperties.isSingleSessionEnabled()) {
      return 0L;
    }
    ValueOperations<String, String> ops = redisTemplate.opsForValue();
    String raw = ops.get(key(username, tenantId));
    if (!StringUtils.hasText(raw)) {
      return 0L;
    }
    try {
      return Long.parseLong(raw);
    } catch (NumberFormatException ignored) {
      return 0L;
    }
  }

  /** 使指定用户的当前会话立即失效（删除 Redis key，已有 JWT 在下次请求时失败）。 */
  public void invalidateSession(String username, String tenantId) {
    redisTemplate.delete(key(username, tenantId));
  }

  public boolean isCurrentSession(String username, String tenantId, long sessionVersion) {
    if (!securityProperties.isSingleSessionEnabled()) {
      return true;
    }
    long current = currentSessionVersion(username, tenantId);
    return current > 0L && current == sessionVersion;
  }

  private Duration sessionStateTtl() {
    Duration ttl = securityProperties.getSessionStateTtl();
    return ttl == null || ttl.isNegative() || ttl.isZero() ? Duration.ofDays(30) : ttl;
  }

  private String key(String username, String tenantId) {
    return KEY_PREFIX + normalize(tenantId) + ":" + normalize(username);
  }

  private String normalize(String value) {
    return value == null ? "unknown" : value.trim().toLowerCase(Locale.ROOT);
  }
}
