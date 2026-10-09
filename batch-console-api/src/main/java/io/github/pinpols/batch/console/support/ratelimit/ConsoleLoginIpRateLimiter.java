package io.github.pinpols.batch.console.support.ratelimit;

import io.github.pinpols.batch.common.logging.LogSanitizer;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.config.ConsoleRateLimitProperties;
import io.github.pinpols.batch.console.config.ConsoleSecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;

/** 统一密码登录和 OIDC 登录发起的客户端 IP 限流及 Redis 故障回退语义。 */
@Slf4j
public class ConsoleLoginIpRateLimiter {

  private final SlidingWindowRateLimiter rateLimiter;
  private final ConsoleRateLimitProperties rateLimitProperties;
  private final ConsoleSecurityProperties securityProperties;
  private final RedisRateLimitCircuitBreaker redisCircuitBreaker;

  public ConsoleLoginIpRateLimiter(
      SlidingWindowRateLimiter rateLimiter,
      ConsoleRateLimitProperties rateLimitProperties,
      ConsoleSecurityProperties securityProperties,
      RedisRateLimitCircuitBreaker redisCircuitBreaker) {
    this.rateLimiter = rateLimiter;
    this.rateLimitProperties = rateLimitProperties;
    this.securityProperties = securityProperties;
    this.redisCircuitBreaker = redisCircuitBreaker;
  }

  public boolean tryAcquire(HttpServletRequest request) {
    String clientIp = resolveClientIp(request);
    if (!redisCircuitBreaker.allowRedisCall()) {
      return true;
    }
    try {
      boolean allowed = rateLimiter.tryAcquire(
          "login:ip:" + clientIp, rateLimitProperties.getLoginIpLimitPerMinute());
      redisCircuitBreaker.recordSuccess();
      return allowed;
    } catch (DataAccessException exception) {
      redisCircuitBreaker.recordFailure();
      log.warn(
          "login rate limiter Redis unavailable; fail-open: ip={}, cause={}",
          LogSanitizer.value(clientIp),
          SwallowedExceptionLogger.summary(exception));
      return true;
    }
  }

  private String resolveClientIp(HttpServletRequest request) {
    if (securityProperties.isTrustForwardedHeaders()) {
      String forwardedFor = request.getHeader("X-Forwarded-For");
      if (Texts.hasText(forwardedFor)) {
        int comma = forwardedFor.indexOf(',');
        return (comma > 0 ? forwardedFor.substring(0, comma) : forwardedFor).trim();
      }
      String realIp = request.getHeader("X-Real-IP");
      if (Texts.hasText(realIp)) {
        return realIp.trim();
      }
    }
    return request.getRemoteAddr();
  }
}
