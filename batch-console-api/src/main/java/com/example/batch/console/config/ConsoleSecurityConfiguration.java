package com.example.batch.console.config;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.constants.CommonErrorMessages;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.console.domain.rbac.support.ConsoleAuthenticationFilter;
import com.example.batch.console.domain.rbac.support.ConsoleMustChangePasswordGuard;
import com.example.batch.console.domain.rbac.support.ConsoleRoles;
import com.example.batch.console.domain.rbac.support.ConsoleSecurityHeadersWriter;
import com.example.batch.console.domain.rbac.support.ConsoleSecurityResponseWriter;
import com.example.batch.console.support.maintenance.MaintenanceModeFilter;
import com.example.batch.console.support.ratelimit.ConsoleRateLimitFilter;
import com.example.batch.console.support.ratelimit.SlidingWindowRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class ConsoleSecurityConfiguration {

  private final ConsoleSecurityProperties properties;
  private final BatchSecurityProperties batchSecurityProperties;
  private static final String[] CSRF_IGNORED_MATCHERS = {
    "/actuator/**",
    "/api/console/auth/login",
    "/api/console/auth/logout",
    "/api/console/auth/public-key",
    "/api/console/auth/token",
    "/api/console/push/vapid-public-key",
    "/api/console/files/fs-download",
    "/console-login.html",
    "/favicon.ico"
  };
  private static final String[] BYPASS_MODE_CSRF_IGNORED_MATCHERS = {"/**"};

  @Bean
  public ConsoleRateLimitFilter consoleRateLimitFilter(
      SlidingWindowRateLimiter rateLimiter,
      ConsoleRateLimitProperties rateLimitProperties,
      ConsoleSecurityResponseWriter responseWriter) {
    return new ConsoleRateLimitFilter(rateLimiter, rateLimitProperties, responseWriter, properties);
  }

  /**
   * 把过滤器链装配所需的协作 bean 聚合成单一持有类,避免 {@link #consoleSecurityFilterChain} 参数 ≥ 7 触发 PMD
   * ExcessiveParameterList(CLAUDE.md 红线 #2)。Spring 按类型逐个注入各成员,装配语义不变。
   */
  @Bean
  public ConsoleSecurityFilterChainComponents consoleSecurityFilterChainComponents(
      ConsoleAuthenticationFilter consoleAuthenticationFilter,
      ConsoleRateLimitFilter consoleRateLimitFilter,
      MaintenanceModeFilter maintenanceModeFilter,
      ConsoleMustChangePasswordGuard mustChangePasswordGuard,
      ConsoleSecurityResponseWriter responseWriter,
      ConsoleSecurityHeadersWriter securityHeadersWriter,
      CorsConfigurationSource consoleCorsConfigurationSource) {
    return new ConsoleSecurityFilterChainComponents(
        consoleAuthenticationFilter,
        consoleRateLimitFilter,
        maintenanceModeFilter,
        mustChangePasswordGuard,
        responseWriter,
        securityHeadersWriter,
        consoleCorsConfigurationSource);
  }

  @Bean
  public SecurityFilterChain consoleSecurityFilterChain(
      HttpSecurity http, ConsoleSecurityFilterChainComponents components) throws Exception {
    ConsoleAuthenticationFilter consoleAuthenticationFilter = components.authenticationFilter();
    ConsoleRateLimitFilter consoleRateLimitFilter = components.rateLimitFilter();
    MaintenanceModeFilter maintenanceModeFilter = components.maintenanceModeFilter();
    ConsoleMustChangePasswordGuard mustChangePasswordGuard = components.mustChangePasswordGuard();
    ConsoleSecurityResponseWriter responseWriter = components.responseWriter();
    ConsoleSecurityHeadersWriter securityHeadersWriter = components.securityHeadersWriter();
    CorsConfigurationSource consoleCorsConfigurationSource = components.corsConfigurationSource();
    http.cors(cors -> cors.configurationSource(consoleCorsConfigurationSource));
    // ADR-030 D7:console 主认证是 HttpOnly cookie。cookie 自动随请求发送,所以 mutating API
    // 必须有 double-submit CSRF 保护。FE axios 已固定读取 XSRF-TOKEN cookie 并回传 X-XSRF-TOKEN。
    // bypass-mode 仅供本地/联调/E2E 使用,这里通过 ignore matcher 放宽,避免禁用 CSRF filter 本身。
    http.csrf(
        csrf ->
            csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .ignoringRequestMatchers(csrfIgnoredMatchers()));
    return http.sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .headers(headers -> headers.addHeaderWriter(securityHeadersWriter))
        // R6 P0-3：禁用 Spring Security HTTP Basic。
        // console 走 ADR-030 §D7 HttpOnly cookie JWT 单一认证路径，HTTP Basic 会让 401 响应附带
        // WWW-Authenticate: Basic realm=...，浏览器弹原生登录框 + 还允许 Authorization: Basic
        // 直接绕过 cookie filter 链。生产环境不可暴露。
        .httpBasic(AbstractHttpConfigurer::disable)
        .exceptionHandling(
            exceptionHandling ->
                exceptionHandling
                    .authenticationEntryPoint(authenticationEntryPoint(responseWriter))
                    .accessDeniedHandler(accessDeniedHandler(responseWriter)))
        .authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus")
                    .permitAll()
                    .requestMatchers(
                        "/api/console/auth/login",
                        "/api/console/auth/logout",
                        "/api/console/auth/public-key",
                        "/api/console/push/vapid-public-key",
                        "/api/console/system/maintenance",
                        "/api/console/system/cron-preview",
                        // FS 后端 presign 代下端点：URL 自带 HMAC 令牌即授权（见
                        // ConsoleFilesystemPresignDownloadController），无登录态；S3 后端
                        // ConditionalOnProperty 不装
                        // controller，permitAll 仅是路径白名单不会引入新攻击面。
                        "/api/console/files/fs-download",
                        "/console-login.html",
                        "/favicon.ico")
                    .permitAll()
                    .requestMatchers("/actuator/loggers/**")
                    .hasAuthority(ConsoleRoles.ADMIN)
                    // P0-1 回退（ADR audit 2026-05-14）：/api/console/** 默认要求至少一个有效角色，
                    // 避免新加 controller 漏加 @PreAuthorize 时被无角色账号访问。
                    // 高危端点已在 controller 上叠加更严格的 @PreAuthorize。
                    .requestMatchers("/api/console/**")
                    .hasAnyAuthority(
                        ConsoleRoles.ADMIN,
                        ConsoleRoles.TENANT_ADMIN,
                        ConsoleRoles.AUDITOR,
                        ConsoleRoles.TENANT_USER,
                        ConsoleRoles.USER)
                    .anyRequest()
                    .authenticated())
        // Auth 先建立 SecurityContext，MaintenanceModeFilter 才能识别 ROLE_ADMIN 旁路；
        // rate limit 仍放在维护拦截之后，维护期被挡请求不消耗限流窗口。
        .addFilterAfter(new CsrfCookieMaterializeFilter(), CsrfFilter.class)
        .addFilterBefore(consoleAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterAfter(maintenanceModeFilter, ConsoleAuthenticationFilter.class)
        .addFilterAfter(consoleRateLimitFilter, MaintenanceModeFilter.class)
        // 首登强制改密守护:认证之后拦截 must_change 账号的一切写操作(白名单除外)。
        .addFilterAfter(mustChangePasswordGuard, ConsoleRateLimitFilter.class)
        .build();
  }

  private String[] csrfIgnoredMatchers() {
    return batchSecurityProperties.isBypassMode()
        ? BYPASS_MODE_CSRF_IGNORED_MATCHERS
        : CSRF_IGNORED_MATCHERS;
  }

  private AuthenticationEntryPoint authenticationEntryPoint(
      ConsoleSecurityResponseWriter responseWriter) {
    return (request, response, authException) ->
        responseWriter.write(
            response,
            HttpStatus.UNAUTHORIZED,
            ResultCode.UNAUTHORIZED,
            CommonErrorMessages.AUTHENTICATION_REQUIRED);
  }

  private AccessDeniedHandler accessDeniedHandler(ConsoleSecurityResponseWriter responseWriter) {
    return (request, response, accessDeniedException) ->
        responseWriter.write(
            response,
            HttpStatus.FORBIDDEN,
            ResultCode.FORBIDDEN,
            CommonErrorMessages.ACCESS_DENIED);
  }

  /**
   * 过滤器链装配协作 bean 聚合体。仅承载注入,不持有行为;成员均为 Spring 单例 bean,由 {@link
   * #consoleSecurityFilterChainComponents} 工厂按类型注入。
   */
  public record ConsoleSecurityFilterChainComponents(
      ConsoleAuthenticationFilter authenticationFilter,
      ConsoleRateLimitFilter rateLimitFilter,
      MaintenanceModeFilter maintenanceModeFilter,
      ConsoleMustChangePasswordGuard mustChangePasswordGuard,
      ConsoleSecurityResponseWriter responseWriter,
      ConsoleSecurityHeadersWriter securityHeadersWriter,
      CorsConfigurationSource corsConfigurationSource) {}

  /** Spring Security 6 默认延迟生成 CSRF token;SPA 需要在首次 GET 时拿到 XSRF-TOKEN cookie。 */
  private static final class CsrfCookieMaterializeFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
      CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
      if (csrfToken != null) {
        csrfToken.getToken();
      }
      filterChain.doFilter(request, response);
    }
  }
}
