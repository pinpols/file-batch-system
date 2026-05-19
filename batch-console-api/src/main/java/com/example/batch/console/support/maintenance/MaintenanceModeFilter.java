package com.example.batch.console.support.maintenance;

import com.example.batch.common.utils.EncodingUtils;
import com.example.batch.console.config.ConsoleMaintenanceProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 维护模式拦截器。
 *
 * <p>{@link ConsoleMaintenanceProperties} 控制:enabled=true 时除白名单外整站 503;readOnly=true 时 GET 放行、写方法
 * 503。
 *
 * <p>顺序:必须在 RateLimit/Auth 之前,避免维护期还做 rate limit/auth 检查浪费资源。
 */
@Component
@RequiredArgsConstructor
public class MaintenanceModeFilter extends OncePerRequestFilter {

  /** 维护期间始终放行的路径(健康检查 + 维护状态本身 + 登录后探活)。 */
  private static final Set<String> ALWAYS_ALLOWED =
      Set.of(
          "/actuator/**",
          "/api/console/auth/check",
          "/api/console/auth/logout",
          "/api/console/system/maintenance");

  private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

  private final ConsoleMaintenanceProperties properties;
  private final ObjectMapper objectMapper;
  private final AntPathMatcher pathMatcher = new AntPathMatcher();

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!properties.isEnabled()) {
      chain.doFilter(request, response);
      return;
    }
    String path = request.getRequestURI();
    if (isAllowedPath(path)) {
      chain.doFilter(request, response);
      return;
    }
    if (properties.isReadOnly() && !WRITE_METHODS.contains(request.getMethod())) {
      // 只读模式下 GET/HEAD/OPTIONS 放行;响应头标记维护中以便前端禁写按钮。
      response.setHeader("X-Maintenance", "read-only");
      chain.doFilter(request, response);
      return;
    }
    writeMaintenanceResponse(response);
  }

  private boolean isAllowedPath(String path) {
    for (String pattern : ALWAYS_ALLOWED) {
      if (pathMatcher.match(pattern, path)) {
        return true;
      }
    }
    return false;
  }

  private void writeMaintenanceResponse(HttpServletResponse response) throws IOException {
    if (response.isCommitted()) {
      return;
    }
    response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
    response.setCharacterEncoding(EncodingUtils.UTF_8);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setHeader("X-Maintenance", properties.isReadOnly() ? "read-only" : "blocked");
    // Retry-After 给客户端 / Cloudflare / nginx 一个合理的退避值(秒)
    long retryAfter = computeRetryAfterSeconds();
    response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("maintenance", true);
    body.put("readOnly", properties.isReadOnly());
    body.put("message", properties.getMessage());
    body.put("etaAt", properties.getEtaAt() != null ? properties.getEtaAt().toString() : null);
    objectMapper.writeValue(response.getWriter(), body);
  }

  private long computeRetryAfterSeconds() {
    Instant eta = properties.getEtaAt();
    if (eta == null) {
      return 60L;
    }
    long secs = eta.getEpochSecond() - Instant.now().getEpochSecond();
    return Math.max(secs, 30L);
  }
}
