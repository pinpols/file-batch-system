package io.github.pinpols.batch.console.support.ratelimit;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.config.ConsoleRateLimitProperties;
import io.github.pinpols.batch.console.config.ConsoleSecurityProperties;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 控制台 API 限流过滤器。
 *
 * <p>两类保护：
 *
 * <ol>
 *   <li><b>登录接口</b>（POST {@code /api/console/auth/login}）：基于客户端 IP 限流， 防止账户暴力破解（默认 10 次/分钟/IP）。
 *   <li><b>敏感变更接口</b>（POST {@code /api/console/ops/triggers/**}）：基于已认证用户名限流， 防止单用户耗尽资源（默认 30
 *       次/分钟/用户）。
 * </ol>
 *
 * <p>超限时返回 HTTP 429，响应体为标准 {@code CommonResponse} 格式。
 */
@Slf4j
@RequiredArgsConstructor
public class ConsoleRateLimitFilter extends OncePerRequestFilter {

  private static final String LOGIN_PATH = "/api/console/auth/login";
  private static final String TRIGGER_PATH_PREFIX = "/api/console/ops/triggers/";

  private final SlidingWindowRateLimiter rateLimiter;
  private final ConsoleRateLimitProperties properties;
  private final ConsoleSecurityResponseWriter responseWriter;
  private final ConsoleSecurityProperties securityProperties;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (!properties.isEnabled()) {
      filterChain.doFilter(request, response);
      return;
    }

    String path = request.getRequestURI();
    String method = request.getMethod();

    // ── 1. 登录接口：IP 限流 ───────────────────────────────────────────────
    if (HttpMethod.POST.matches(method) && LOGIN_PATH.equals(path)) {
      String ip = resolveClientIp(request);
      String key = "login:ip:" + ip;
      if (!tryAcquireFailOpen(key, properties.getLoginIpLimitPerMinute(), "login", ip)) {
        log.warn("登录限流触发：ip={} path={}", ip, path);
        responseWriter.write(
            response, HttpStatus.TOO_MANY_REQUESTS, ResultCode.RATE_LIMITED, "登录请求过于频繁，请稍后重试");
        return;
      }
    }

    // ── 2. 敏感操作接口：用户限流 ─────────────────────────────────────────
    if (HttpMethod.POST.matches(method) && path.startsWith(TRIGGER_PATH_PREFIX)) {
      String username = resolveUsername();
      if (Texts.hasText(username)) {
        String key = "sensitive:user:" + username;
        if (!tryAcquireFailOpen(
            key, properties.getSensitiveOpUserLimitPerMinute(), "sensitive", username)) {
          log.warn("敏感操作限流触发：user={} path={}", username, path);
          responseWriter.write(
              response, HttpStatus.TOO_MANY_REQUESTS, ResultCode.RATE_LIMITED, "操作请求过于频繁，请稍后重试");
          return;
        }
      }
    }

    filterChain.doFilter(request, response);
  }

  /**
   * R-4.1：Redis 不可达时 <b>fail-open</b>（放行并记 warn），避免把限流模块的 可用性故障升级为整站不可用。业务正确性由上游 DDoS 保护 + Grafana
   * 告警回退。
   *
   * @return true 表示放行（正常通过 / Redis 故障回退），false 表示超限拒绝
   */
  private boolean tryAcquireFailOpen(
      String key, int limitPerMinute, String category, String identity) {
    try {
      return rateLimiter.tryAcquire(key, limitPerMinute);
    } catch (DataAccessException ex) {
      log.warn(
          "rate limiter Redis unavailable — fail-open: category={}, identity={}, cause={}",
          category,
          identity,
          ex.getMessage());
      return true;
    }
  }

  /**
   * 解析客户端真实 IP。仅当 {@code batch.console.security.trust-forwarded-headers=true} 时才信任反代下发的 {@code
   * X-Forwarded-For} / {@code X-Real-IP}（应用挂在受信反代/Ingress 之后才该开），否则直接走 {@code RemoteAddr},防 {@code
   * curl -H 'X-Forwarded-For: 1.2.3.4'} 伪造源 IP 绕过限流。
   */
  private String resolveClientIp(HttpServletRequest request) {
    if (securityProperties.isTrustForwardedHeaders()) {
      String xff = request.getHeader("X-Forwarded-For");
      if (Texts.hasText(xff)) {
        int comma = xff.indexOf(',');
        return (comma > 0 ? xff.substring(0, comma) : xff).trim();
      }
      String realIp = request.getHeader("X-Real-IP");
      if (Texts.hasText(realIp)) {
        return realIp.trim();
      }
    }
    return request.getRemoteAddr();
  }

  private String resolveUsername() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return (auth != null && auth.isAuthenticated()) ? auth.getName() : null;
  }
}
