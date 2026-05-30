package com.example.batch.console.domain.rbac.support;

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
 *   <li><b>JWT</b>（HttpOnly cookie {@code batch_console_token}）：主认证方式，走 {@link
 *       ConsoleJwtService#authenticate}。ADR-030 §D7 Stage B 收尾（2026-05-15）后已删 Authorization Bearer
 *       header fallback —— 前端 axios {@code withCredentials=true} 自动带 cookie；运维脚本用 curl {@code
 *       --cookie}。
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
   * SSE ticket 在 REQUEST 分派被 {@code getAndDelete} 一次性消费后，缓存到当次 request 的 attribute； ASYNC / ERROR
   * 分派再次进入本过滤器时直接复用，避免二次 validate 拿不到值导致认证空缺、 上抛 {@code AuthorizationDeniedException} + Tomcat
   * ERROR 级日志雪崩（response 已 commit 写不下 403）。
   */
  static final String TICKET_PRINCIPAL_ATTR =
      ConsoleAuthenticationFilter.class.getName() + ".TICKET_PRINCIPAL";

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
        // ASYNC/ERROR 分派复用 REQUEST 阶段已消费的 ticket 解析结果，避免二次 validate
        // 命中已删 key 后假装匿名 → 落入 AuthorizationFilter 抛 AccessDenied → response committed 雪崩
        SseTicketService.TicketPayload payload =
            (SseTicketService.TicketPayload) request.getAttribute(TICKET_PRINCIPAL_ATTR);
        if (payload == null) {
          payload = sseTicketService.validate(sseTicket);
          if (payload != null) {
            request.setAttribute(TICKET_PRINCIPAL_ATTR, payload);
          }
        }
        if (payload != null) {
          // R4-P1-1：用 ticket 签发时绑定的真实角色集；空角色（旧数据兼容）走配置默认值兜底。
          LinkedHashSet<String> authorities =
              payload.authorities().isEmpty()
                  ? new LinkedHashSet<>(properties.getDefaultAuthorities())
                  : new LinkedHashSet<>(payload.authorities());
          ConsolePrincipal principal =
              new ConsolePrincipal(payload.username(), payload.tenantId(), authorities);
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

          // D7 Stage B 切到 HttpOnly cookie 后,浏览器对任何同源请求都自动带 cookie
          // (含 /auth/login、/auth/logout)。失效 cookie 在公开认证端点不应阻塞请求,
          // 否则用户陷死:cookie 一旦过期,连重新登录换新 cookie 的机会都没有。
          // 这些端点本来就在 SecurityConfig 标了 permitAll,放行给下游 controller 自处理。
          if (isPublicAuthPath(request)) {
            filterChain.doFilter(request, response);
            return;
          }

          // A3-B fix(2026-05-29):bypass-mode 开启时 JWT 失败应降级到 bypass 分支,
          // 否则 admin 带过期/失效 cookie 调任何接口直接 401,无法访问 admin 端点(本意是
          // dev/test 完全免鉴权)。生产关闭 bypass-mode 后此分支不生效,行为不变。
          if (batchSecurityProperties.isBypassMode()) {
            // fall through to bypass-mode handler below
          } else {
            responseWriter.write(
                response,
                HttpStatus.UNAUTHORIZED,
                ResultCode.UNAUTHORIZED,
                CommonErrorMessages.INVALID_CONSOLE_JWT);
            return;
          }
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

  /** ADR-030 §D7：HttpOnly cookie 名（与登录响应 Set-Cookie 同步）。 */
  private static final String CONSOLE_TOKEN_COOKIE = "batch_console_token";

  /**
   * 与 {@code ConsoleSecurityConfiguration} permitAll 列表对齐的公开认证端点。 失效 cookie 命中这些路径时不应 401,放行给
   * controller 处理凭证校验和换发新 cookie。
   */
  private static final Set<String> PUBLIC_AUTH_PATHS =
      Set.of("/api/console/auth/login", "/api/console/auth/logout", "/api/console/auth/public-key");

  private boolean isPublicAuthPath(HttpServletRequest request) {
    return PUBLIC_AUTH_PATHS.contains(request.getRequestURI());
  }

  /**
   * ADR-030 §D7 Stage B 收尾（2026-05-15）：HttpOnly cookie 是 console 端唯一 JWT 入口。
   *
   * <p>Authorization Bearer header fallback 已删除，所有客户端统一走 cookie：
   *
   * <ul>
   *   <li>前端：axios {@code withCredentials=true}，浏览器自动带 cookie
   *   <li>运维脚本：curl {@code --cookie "batch_console_token=${BATCH_CONSOLE_TOKEN}"}
   *       （heal-drain-timeout.sh / trigger-compensation.sh 同步迁移）
   * </ul>
   *
   * <p>orchestrator {@code /internal/**} 走另一个 {@code X-Internal-Secret} 通道， 不在本 filter 范围内，5 个
   * heal-* 脚本不受影响。
   *
   * <p>Header 入站不再被识别为 token：保留 method 签名是为了上游 SSE-ticket 路径 fallthrough 调用方便。
   */
  private String resolveBearerToken(HttpServletRequest request) {
    return resolveCookieToken(request);
  }

  private String resolveCookieToken(HttpServletRequest request) {
    jakarta.servlet.http.Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (jakarta.servlet.http.Cookie cookie : cookies) {
      if (CONSOLE_TOKEN_COOKIE.equals(cookie.getName())) {
        String value = cookie.getValue();
        if (Texts.hasText(value)) {
          return value.trim();
        }
      }
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
