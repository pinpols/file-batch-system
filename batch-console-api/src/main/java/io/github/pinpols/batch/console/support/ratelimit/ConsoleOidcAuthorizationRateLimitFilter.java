package io.github.pinpols.batch.console.support.ratelimit;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.web.ServletRequestPaths;
import io.github.pinpols.batch.console.config.ConsoleRateLimitProperties;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/** 在 Spring OAuth2 重定向过滤器前限制匿名 OIDC 登录发起请求。 */
public class ConsoleOidcAuthorizationRateLimitFilter extends OncePerRequestFilter {

  private static final String AUTHORIZATION_PATH_PREFIX = "/oauth2/authorization/";

  private final ConsoleRateLimitProperties properties;
  private final ConsoleLoginIpRateLimiter loginIpRateLimiter;
  private final ConsoleSecurityResponseWriter responseWriter;

  public ConsoleOidcAuthorizationRateLimitFilter(
      ConsoleRateLimitProperties properties,
      ConsoleLoginIpRateLimiter loginIpRateLimiter,
      ConsoleSecurityResponseWriter responseWriter) {
    this.properties = properties;
    this.loginIpRateLimiter = loginIpRateLimiter;
    this.responseWriter = responseWriter;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String path = ServletRequestPaths.applicationPath(request);
    boolean oidcAuthorizationStart =
        HttpMethod.GET.matches(request.getMethod()) && path.startsWith(AUTHORIZATION_PATH_PREFIX);
    if (!properties.isEnabled()
        || !oidcAuthorizationStart
        || loginIpRateLimiter.tryAcquire(request)) {
      filterChain.doFilter(request, response);
      return;
    }
    responseWriter.write(
        response, HttpStatus.TOO_MANY_REQUESTS, ResultCode.RATE_LIMITED, "登录请求过于频繁，请稍后重试");
  }
}
