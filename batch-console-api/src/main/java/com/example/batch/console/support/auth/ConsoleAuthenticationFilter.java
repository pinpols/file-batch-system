package com.example.batch.console.support.auth;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.constants.CommonErrorMessages;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.utils.Texts;
import com.example.batch.console.config.ConsoleSecurityProperties;
import com.example.batch.console.support.SseTicketService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 控制台请求认证过滤器：按优先级依次匹配 3 条认证链，首个命中即填充 {@link ConsolePrincipal}，都不命中则放行给下游 （下游的 {@code @PreAuthorize}
 * 会拒绝未认证请求）。
 *
 * <ol>
 *   <li><b>SSE ticket</b>（{@code ?ticket=xxx}）：{@link SseTicketService} 签发的一次性凭证， 专为浏览器 EventSource
 *       而设（EventSource 不能带 Authorization header）。
 *   <li><b>JWT</b>（{@code Authorization: Bearer}）：主认证方式，走 {@link ConsoleJwtService#authenticate}。
 *   <li><b>bypass-mode</b>：仅测试 profile 使用，可从 header 读 username/tenant/roles，放行任意角色 （生产禁用，由 {@code
 *       batchSecurityProperties.bypass-mode} 控制）。
 * </ol>
 *
 * <p>历史：第 3 条 Legacy X-Console-Token 共享密钥路径已于 2026-04-30 物理删除（S5-d）。详见
 * docs/analysis/project-assessment-2026-04-29.md §8 S5-d 修订记录。
 *
 * <p>{@code finally clearContext()} 兜底：不论哪条认证链执行或抛异常，都清理 {@code SecurityContextHolder}， 防止容器线程池复用时
 * ThreadLocal 污染下一个请求。
 */
@Component
@RequiredArgsConstructor
public class ConsoleAuthenticationFilter extends OncePerRequestFilter {

  /**
   * Spring MVC 异步完成（例如 SSE 的 {@code SseEmitter} / Streaming 等）会再走一轮 {@code DispatcherType.ASYNC}。
   *
   * <p>{@link OncePerRequestFilter} 默认跳过 ASYNC 分派，则本轮不会重建 {@link SecurityContextHolder}，而 Spring
   * Security 6+ 的 {@code AuthorizationFilter} 仍会对 ASYNC 执行校验 → 误判未认证并抛出 {@code
   * AuthorizationDeniedException}。
   */
  @Override
  protected boolean shouldNotFilterAsyncDispatch() {
    return false;
  }

  /** 错误分派与 ASYNC 同理：需要在本过滤器内按同一规则（JWT / SSE ticket / bypass）恢复认证，避免仅 ERROR 轮次匿名访问被拒绝。 */
  @Override
  protected boolean shouldNotFilterErrorDispatch() {
    return false;
  }

  private final ConsoleSecurityProperties properties;
  private final BatchSecurityProperties batchSecurityProperties;
  private final ConsoleJwtService jwtService;
  private final ConsoleSecurityResponseWriter responseWriter;
  private final SseTicketService sseTicketService;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    try {
      if (!properties.isEnabled() && !batchSecurityProperties.isBypassMode()) {
        filterChain.doFilter(request, response);
        return;
      }

      // SSE ticket 鉴权：EventSource 不能设 header，用一次性 ticket 替代
      String sseTicket = request.getParameter("ticket");
      if (Texts.hasText(sseTicket)) {
        String ticketValue = sseTicketService.validate(sseTicket);
        if (ticketValue != null) {
          String[] parts = ticketValue.split(":", 2);
          String ticketUser = parts[0];
          String ticketTenant = parts.length > 1 ? parts[1] : "";
          ConsolePrincipal principal =
              new ConsolePrincipal(
                  ticketUser,
                  ticketTenant,
                  properties.getDefaultAuthorities().stream()
                      .collect(Collectors.toCollection(LinkedHashSet::new)));
          setAuthentication(principal, "sse-ticket");
          filterChain.doFilter(request, response);
          return;
        }
      }

      String bearerToken = resolveBearerToken(request);
      if (Texts.hasText(bearerToken)) {
        try {
          ConsolePrincipal principal = jwtService.authenticate(bearerToken);
          setAuthentication(principal, bearerToken);
          filterChain.doFilter(request, response);
          return;
        } catch (Exception exception) {
          SwallowedExceptionLogger.warn(
              ConsoleAuthenticationFilter.class, "catch:Exception", exception);

          responseWriter.write(
              response,
              HttpStatus.UNAUTHORIZED,
              ResultCode.UNAUTHORIZED,
              CommonErrorMessages.INVALID_CONSOLE_JWT);
          return;
        }
      }

      if (batchSecurityProperties.isBypassMode()) {
        try {
          String username = resolveUsername(request);
          String tenantId = resolveTenant(request);
          Set<SimpleGrantedAuthority> authorities = resolveAuthorities(request);
          ConsolePrincipal principal =
              new ConsolePrincipal(
                  username,
                  tenantId,
                  authorities.stream()
                      .map(SimpleGrantedAuthority::getAuthority)
                      .collect(Collectors.toCollection(LinkedHashSet::new)));
          setAuthentication(principal, "bypass-mode");
        } catch (IllegalArgumentException exception) {
          SwallowedExceptionLogger.info(
              ConsoleAuthenticationFilter.class, "catch:IllegalArgumentException", exception);

          responseWriter.write(
              response,
              HttpStatus.FORBIDDEN,
              ResultCode.FORBIDDEN,
              CommonErrorMessages.TENANT_MISMATCH);
          return;
        }
      }

      filterChain.doFilter(request, response);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private void setAuthentication(ConsolePrincipal principal, String credentials) {
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(
            principal,
            credentials,
            principal.authorities().stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  private String resolveBearerToken(HttpServletRequest request) {
    // 5.4: 仅从 Authorization header 读取 JWT，不再接受 URL query token（防止日志/Referer 泄露）
    String authorization = request.getHeader("Authorization");
    if (Texts.hasText(authorization) && authorization.startsWith("Bearer ")) {
      return authorization.substring(7).trim();
    }
    return null;
  }

  private String resolveUsername(HttpServletRequest request) {
    String username = request.getHeader(properties.getUserHeader());
    if (!Texts.hasText(username)) {
      username = batchSecurityProperties.isBypassMode() ? "testing-console-user" : "console-user";
    }
    return username;
  }

  private String resolveTenant(HttpServletRequest request) {
    String tenantId = request.getHeader(properties.getTenantHeader());
    if (!Texts.hasText(tenantId)) {
      tenantId = properties.getDefaultTenantId();
    }
    if (!properties.getAllowedTenants().isEmpty()
        && !properties.getAllowedTenants().contains(tenantId)) {
      throw new IllegalArgumentException("tenant not allowed");
    }
    return tenantId;
  }

  private Set<SimpleGrantedAuthority> resolveAuthorities(HttpServletRequest request) {
    String rolesHeader = request.getHeader(properties.getRoleHeader());
    if (!Texts.hasText(rolesHeader)) {
      return properties.getDefaultAuthorities().stream()
          .map(SimpleGrantedAuthority::new)
          .collect(Collectors.toCollection(LinkedHashSet::new));
    }
    return Arrays.stream(rolesHeader.split(","))
        .map(String::trim)
        .filter(role -> !role.isBlank())
        .map(SimpleGrantedAuthority::new)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }
}
