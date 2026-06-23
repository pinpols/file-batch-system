package io.github.pinpols.batch.console.support.maintenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.utils.EncodingUtils;
import io.github.pinpols.batch.console.domain.rbac.support.ConsolePrincipal;
import io.github.pinpols.batch.console.support.maintenance.MaintenanceStateHolder.MaintenanceState;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 维护模式拦截器。
 *
 * <p>{@link MaintenanceStateHolder} 控制运行时状态:enabled=true 时除白名单/admin 外整站 503; readOnly=true 时 GET
 * 放行、写方法 503。
 *
 * <p><b>ROLE_ADMIN 旁路</b>:已认证 admin 用户(JWT 含 ROLE_ADMIN)在维护期 **全程透传**,让运维灰度时仍可登 console 操作。响应头加
 * {@code X-Maintenance: admin-bypass} 让前端 banner 标识"当前为维护期 admin 旁路"。
 *
 * <p>顺序:必须在 RateLimit/Auth 之前,避免维护期还做 rate limit/auth 检查浪费资源。 **admin 旁路晚于 Auth filter 处理**(JWT
 * 验签在前),这里只读 SecurityContextHolder。
 */
@Component
@RequiredArgsConstructor
public class MaintenanceModeFilter extends OncePerRequestFilter {

  /** 维护期间始终放行的路径(健康检查 + 维护状态本身 + 登录后探活 + admin 热更新端点)。 */
  private static final Set<String> ALWAYS_ALLOWED =
      Set.of(
          "/actuator/**",
          "/api/console/auth/check",
          "/api/console/auth/logout",
          "/api/console/system/maintenance",
          "/api/console/admin/system/maintenance");

  private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

  private static final String ROLE_ADMIN = "ROLE_ADMIN";

  private final MaintenanceStateHolder stateHolder;
  private final ObjectMapper objectMapper;
  private final AntPathMatcher pathMatcher = new AntPathMatcher();

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    MaintenanceState state = stateHolder.current();
    if (!state.enabled()) {
      chain.doFilter(request, response);
      return;
    }
    String path = request.getRequestURI();
    if (isAllowedPath(path)) {
      chain.doFilter(request, response);
      return;
    }
    if (isAdmin()) {
      // admin 旁路:维护期间运维仍可登 console 操作,头部 banner 提示当前为维护期
      response.setHeader("X-Maintenance", "admin-bypass");
      chain.doFilter(request, response);
      return;
    }
    if (state.readOnly() && !WRITE_METHODS.contains(request.getMethod())) {
      // 只读模式下 GET/HEAD/OPTIONS 放行;响应头标记维护中以便前端禁写按钮。
      response.setHeader("X-Maintenance", "read-only");
      chain.doFilter(request, response);
      return;
    }
    writeMaintenanceResponse(response, state);
  }

  private boolean isAllowedPath(String path) {
    for (String pattern : ALWAYS_ALLOWED) {
      if (pathMatcher.match(pattern, path)) {
        return true;
      }
    }
    return false;
  }

  /** SecurityContext 里有 ROLE_ADMIN 即旁路。Auth filter 已完成 JWT 验签,这里只读。 */
  private boolean isAdmin() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated()) {
      return false;
    }
    // 双重判定:authorities 直接含 ROLE_ADMIN,或 principal 是 ConsolePrincipal 且 authorities 含。
    // 兼容 bypass-mode 注入的 anonymous 与 JWT 正常认证两种路径。
    for (GrantedAuthority authority : auth.getAuthorities()) {
      if (ROLE_ADMIN.equals(authority.getAuthority())) {
        return true;
      }
    }
    if (auth.getPrincipal() instanceof ConsolePrincipal p && p.authorities() != null) {
      for (String a : p.authorities()) {
        if (ROLE_ADMIN.equals(a)) {
          return true;
        }
      }
    }
    return false;
  }

  private void writeMaintenanceResponse(HttpServletResponse response, MaintenanceState state)
      throws IOException {
    if (response.isCommitted()) {
      return;
    }
    response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
    response.setCharacterEncoding(EncodingUtils.UTF_8);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setHeader("X-Maintenance", state.readOnly() ? "read-only" : "blocked");
    // Retry-After 给客户端 / Cloudflare / nginx 一个合理的退避值(秒)
    long retryAfter = computeRetryAfterSeconds(state.etaAt());
    response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("maintenance", true);
    body.put("readOnly", state.readOnly());
    body.put("message", state.message());
    body.put("etaAt", state.etaAt() != null ? state.etaAt().toString() : null);
    body.put("affectedServices", state.affectedServices());
    objectMapper.writeValue(response.getWriter(), body);
  }

  private long computeRetryAfterSeconds(Instant eta) {
    if (eta == null) {
      return 60L;
    }
    long secs = eta.getEpochSecond() - Instant.now().getEpochSecond();
    return Math.max(secs, 30L);
  }
}
