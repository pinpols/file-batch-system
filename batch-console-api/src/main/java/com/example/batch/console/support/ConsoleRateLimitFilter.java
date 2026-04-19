package com.example.batch.console.support;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.console.config.ConsoleRateLimitProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import com.example.batch.common.utils.Texts;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 控制台 API 限流过滤器。
 *
 * <p>两类保护：
 *
 * <ol>
 *   <li><b>登录接口</b>（POST {@code /api/console/auth/login}）：基于客户端 IP 限流， 防止账户暴力破解（默认 10 次/分钟/IP）。
 *   <li><b>敏感变更接口</b>（POST {@code /api/console/triggers/**}）：基于已认证用户名限流， 防止单用户耗尽资源（默认 30 次/分钟/用户）。
 * </ol>
 *
 * <p>超限时返回 HTTP 429，响应体为标准 {@code CommonResponse} 格式。
 */
@Slf4j
@RequiredArgsConstructor
public class ConsoleRateLimitFilter extends OncePerRequestFilter {

  private static final String LOGIN_PATH = "/api/console/auth/login";
  private static final String TRIGGER_PATH_PREFIX = "/api/console/triggers/";

  private final SlidingWindowRateLimiter rateLimiter;
  private final ConsoleRateLimitProperties properties;
  private final ConsoleSecurityResponseWriter responseWriter;

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
      if (!rateLimiter.tryAcquire(key, properties.getLoginIpLimitPerMinute())) {
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
        if (!rateLimiter.tryAcquire(key, properties.getSensitiveOpUserLimitPerMinute())) {
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
   * 解析客户端真实 IP，优先取反向代理头 {@code X-Forwarded-For} 的第一个地址， 其次取 {@code X-Real-IP}，最后取 {@code
   * RemoteAddr}。
   */
  private String resolveClientIp(HttpServletRequest request) {
    String xff = request.getHeader("X-Forwarded-For");
    if (Texts.hasText(xff)) {
      int comma = xff.indexOf(',');
      return (comma > 0 ? xff.substring(0, comma) : xff).trim();
    }
    String realIp = request.getHeader("X-Real-IP");
    if (Texts.hasText(realIp)) {
      return realIp.trim();
    }
    return request.getRemoteAddr();
  }

  private String resolveUsername() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return (auth != null && auth.isAuthenticated()) ? auth.getName() : null;
  }
}
