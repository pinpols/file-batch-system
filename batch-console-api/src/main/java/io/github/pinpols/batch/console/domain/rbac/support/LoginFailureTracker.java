package io.github.pinpols.batch.console.domain.rbac.support;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.config.LoginProtectionProperties;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 登录失败计数器(Redis Sorted Set 滑动窗口)。按<b>账号</b>与<b>IP</b>两个维度分别计数,窗口默认 15 分钟。
 *
 * <p>与 {@code SlidingWindowRateLimiter} 的区别:后者是"达到上限即拒绝"的 1 分钟硬限流;本类是"记录失败次数 + 随时读取窗口内计数"的风控信号源,
 * 窗口可配(15min),且<b>登录成功立即清零账号计数</b>(IP 计数不清,IP 是共享资源不因单账号成功而放行)。
 *
 * <p>算法:
 *
 * <ul>
 *   <li>{@code recordFailure}:ZADD 当前时刻 + ZREMRANGEBYSCORE 清窗口外 + EXPIRE,返回清理后窗口内计数
 *   <li>{@code currentFailures}:ZREMRANGEBYSCORE 清窗口外 + ZCARD 读计数(只读不写)
 *   <li>{@code clearAccount}:DEL 账号 key
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class LoginFailureTracker {

  private static final String ACCOUNT_KEY_PREFIX = "login:fail:account:";
  private static final String IP_KEY_PREFIX = "login:fail:ip:";

  /** ZADD member + 清窗口外 + EXPIRE,返回窗口内计数。KEYS[1]=key ARGV: now, windowStart, member, ttlSeconds。 */
  private static final DefaultRedisScript<Long> RECORD_SCRIPT;

  /** 清窗口外 + ZCARD 读计数(只读语义,但需写删除过期成员)。KEYS[1]=key ARGV: windowStart。 */
  private static final DefaultRedisScript<Long> COUNT_SCRIPT;

  static {
    RECORD_SCRIPT = new DefaultRedisScript<>();
    RECORD_SCRIPT.setResultType(Long.class);
    RECORD_SCRIPT.setScriptText(
        "redis.call('ZADD', KEYS[1], ARGV[1], ARGV[3]) "
            + "redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[2]) "
            + "redis.call('EXPIRE', KEYS[1], ARGV[4]) "
            + "return redis.call('ZCARD', KEYS[1])");

    COUNT_SCRIPT = new DefaultRedisScript<>();
    COUNT_SCRIPT.setResultType(Long.class);
    COUNT_SCRIPT.setScriptText(
        "redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1]) "
            + "return redis.call('ZCARD', KEYS[1])");
  }

  private final StringRedisTemplate redisTemplate;
  private final BatchDateTimeSupport dateTimeSupport;
  private final LoginProtectionProperties properties;

  /** 记一次失败(账号 + IP 各 +1),返回两维度中较大的窗口内计数(供阈值判定)。 */
  public long recordFailure(String username, String clientIp) {
    long accountCount = record(ACCOUNT_KEY_PREFIX + normalize(username));
    long ipCount = record(IP_KEY_PREFIX + normalize(clientIp));
    return Math.max(accountCount, ipCount);
  }

  /** 读账号 + IP 两维度窗口内失败计数的较大者(只读)。 */
  public long currentFailures(String username, String clientIp) {
    long accountCount = count(ACCOUNT_KEY_PREFIX + normalize(username));
    long ipCount = count(IP_KEY_PREFIX + normalize(clientIp));
    return Math.max(accountCount, ipCount);
  }

  /** 登录成功:清零该账号失败计数(IP 计数保留——IP 是共享资源)。 */
  public void clearAccount(String username) {
    redisTemplate.delete(ACCOUNT_KEY_PREFIX + normalize(username));
  }

  private long record(String key) {
    long now = dateTimeSupport.currentEpochMillis();
    long windowStart = now - windowMillis();
    long ttlSeconds = (windowMillis() / 1000) + 1;
    Long count =
        redisTemplate.execute(
            RECORD_SCRIPT,
            List.of("rate_limit:" + key),
            String.valueOf(now),
            String.valueOf(windowStart),
            UUID.randomUUID().toString(),
            String.valueOf(ttlSeconds));
    return count == null ? 0L : count;
  }

  private long count(String key) {
    long windowStart = dateTimeSupport.currentEpochMillis() - windowMillis();
    Long count =
        redisTemplate.execute(
            COUNT_SCRIPT, List.of("rate_limit:" + key), String.valueOf(windowStart));
    return count == null ? 0L : count;
  }

  private long windowMillis() {
    return (long) properties.getFailWindowMinutes() * 60_000L;
  }

  private static String normalize(String raw) {
    return raw == null ? "" : raw.trim().toLowerCase();
  }
}
