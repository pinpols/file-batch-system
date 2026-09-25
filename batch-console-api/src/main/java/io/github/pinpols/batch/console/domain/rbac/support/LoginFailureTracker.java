package io.github.pinpols.batch.console.domain.rbac.support;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.config.LoginProtectionProperties;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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
  private static final String RATE_LIMIT_KEY_PREFIX = "rate_limit:";

  private final LoginFailureStore failureStore;
  private final BatchDateTimeSupport dateTimeSupport;
  private final LoginProtectionProperties properties;

  /** 记一次失败(账号 + IP 各 +1),返回两维度中较大的窗口内计数(供阈值判定)。 */
  public long recordFailure(String username, String clientIp) {
    long accountCount = recordFailureAttempt(ACCOUNT_KEY_PREFIX + normalize(username));
    long ipCount = recordFailureAttempt(IP_KEY_PREFIX + normalize(clientIp));
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
    failureStore.delete(RATE_LIMIT_KEY_PREFIX + ACCOUNT_KEY_PREFIX + normalize(username));
  }

  private long recordFailureAttempt(String key) {
    long now = dateTimeSupport.currentEpochMillis();
    long windowStart = now - windowMillis();
    long ttlSeconds = (windowMillis() / 1000) + 1;
    return failureStore.recordFailure(
        RATE_LIMIT_KEY_PREFIX + key, now, windowStart, UUID.randomUUID().toString(), ttlSeconds);
  }

  private long count(String key) {
    long windowStart = dateTimeSupport.currentEpochMillis() - windowMillis();
    return failureStore.count(RATE_LIMIT_KEY_PREFIX + key, windowStart);
  }

  private long windowMillis() {
    return properties.getFailWindowMinutes() * 60_000L;
  }

  private static String normalize(String raw) {
    return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
  }
}
