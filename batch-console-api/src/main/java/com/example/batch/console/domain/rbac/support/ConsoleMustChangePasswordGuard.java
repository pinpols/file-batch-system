package com.example.batch.console.domain.rbac.support;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.console.domain.rbac.mapper.ConsoleUserAccountMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 首登强制改密守护:对处于 {@code must_change_password=true} 状态的账号,拦截一切「敏感操作」(写方法)直到改密。
 *
 * <p>设计要点(最小侵入):
 *
 * <ul>
 *   <li><b>不改 JWT / ConsolePrincipal</b>:flag 不进 token,按已认证 username 查 DB 判定;短 TTL Caffeine
 *       缓存(10s)摊薄查询。
 *   <li><b>只拦写方法</b>:POST/PUT/PATCH/DELETE。GET 放行 ——FE 需 {@code /me}、改密页等只读路径正常工作。
 *   <li><b>白名单放行</b>:登录态自助端点(改密 / 登出 / token / 当前画像),否则用户陷死无法改密。
 *   <li>命中 → 403 + {@code error.password.must_change};FE 据登录响应的 mustChangePassword 标记跳改密页。
 * </ul>
 *
 * <p>过滤器链位置:置于认证之后(SecurityContext 已填充),才能拿到已认证 username。
 */
@Component
@RequiredArgsConstructor
public class ConsoleMustChangePasswordGuard extends OncePerRequestFilter {

  private final ConsoleUserAccountMapper userAccountMapper;

  /** must_change 期间仍放行的登录态端点(改密自助 + 会话管理 + 只读画像)。 */
  private static final Set<String> ALLOWED_PATHS =
      Set.of(
          "/api/console/auth/change-password",
          "/api/console/auth/logout",
          "/api/console/auth/token",
          "/api/console/auth/me",
          "/api/console/auth/check");

  private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

  /** 短 TTL 缓存:username → mustChangePassword,避免每个写请求都查库。改密后 ≤10s 内自动放行(配合踢会话强制重登)。 */
  private final Cache<String, Boolean> flagCache =
      Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(10)).maximumSize(10_000).build();

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (!isGuarded(request)) {
      filterChain.doFilter(request, response);
      return;
    }
    String username = currentUsername();
    if (username != null && mustChangePassword(username)) {
      response.setStatus(HttpStatus.FORBIDDEN.value());
      response.setContentType("application/json;charset=UTF-8");
      response
          .getWriter()
          .write(
              "{\"code\":\""
                  + ResultCode.FORBIDDEN.name()
                  + "\",\"message\":\"password change required before any sensitive operation\","
                  + "\"messageKey\":\"error.password.must_change\"}");
      return;
    }
    filterChain.doFilter(request, response);
  }

  private boolean isGuarded(HttpServletRequest request) {
    if (!WRITE_METHODS.contains(request.getMethod())) {
      return false;
    }
    String uri = request.getRequestURI();
    return uri != null && uri.startsWith("/api/console/") && !ALLOWED_PATHS.contains(uri);
  }

  private boolean mustChangePassword(String username) {
    Boolean cached = flagCache.getIfPresent(username);
    if (cached != null) {
      return cached;
    }
    boolean flag =
        userAccountMapper
            .findByUsernameIgnoreCase(username)
            .map(account -> account.isMustChangePassword())
            .orElse(false);
    flagCache.put(username, flag);
    return flag;
  }

  private String currentUsername() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null) {
      return null;
    }
    if (auth.getPrincipal() instanceof ConsolePrincipal principal) {
      return principal.username();
    }
    return auth.getName();
  }
}
