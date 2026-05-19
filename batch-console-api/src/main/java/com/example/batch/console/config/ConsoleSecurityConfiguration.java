package com.example.batch.console.config;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.constants.CommonErrorMessages;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.console.support.auth.ConsoleAuthenticationFilter;
import com.example.batch.console.support.auth.ConsoleRoles;
import com.example.batch.console.support.auth.ConsoleSecurityHeadersWriter;
import com.example.batch.console.support.auth.ConsoleSecurityResponseWriter;
import com.example.batch.console.support.maintenance.MaintenanceModeFilter;
import com.example.batch.console.support.ratelimit.ConsoleRateLimitFilter;
import com.example.batch.console.support.ratelimit.SlidingWindowRateLimiter;
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
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class ConsoleSecurityConfiguration {

  private final ConsoleSecurityProperties properties;
  private final BatchSecurityProperties batchSecurityProperties;

  @Bean
  public ConsoleRateLimitFilter consoleRateLimitFilter(
      SlidingWindowRateLimiter rateLimiter,
      ConsoleRateLimitProperties rateLimitProperties,
      ConsoleSecurityResponseWriter responseWriter) {
    return new ConsoleRateLimitFilter(rateLimiter, rateLimitProperties, responseWriter, properties);
  }

  @Bean
  public SecurityFilterChain consoleSecurityFilterChain(
      HttpSecurity http,
      ConsoleAuthenticationFilter consoleAuthenticationFilter,
      ConsoleRateLimitFilter consoleRateLimitFilter,
      MaintenanceModeFilter maintenanceModeFilter,
      ConsoleSecurityResponseWriter responseWriter,
      ConsoleSecurityHeadersWriter securityHeadersWriter,
      CorsConfigurationSource consoleCorsConfigurationSource)
      throws Exception {
    return http.cors(cors -> cors.configurationSource(consoleCorsConfigurationSource))
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
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
                        "/console-login.html",
                        "/favicon.ico")
                    .permitAll()
                    .requestMatchers("/actuator/loggers/**")
                    .hasAuthority(ConsoleRoles.ADMIN)
                    // P0-1 兜底（ADR audit 2026-05-14）：/api/console/** 默认要求至少一个有效角色，
                    // 避免新加 controller 漏加 @PreAuthorize 时被无角色账号访问。
                    // 高危端点已在 controller 上叠加更严格的 @PreAuthorize。
                    .requestMatchers("/api/console/**")
                    .hasAnyAuthority(
                        ConsoleRoles.ADMIN,
                        ConsoleRoles.CONFIG_ADMIN,
                        ConsoleRoles.AUDITOR,
                        ConsoleRoles.TENANT_USER,
                        ConsoleRoles.USER)
                    .anyRequest()
                    .authenticated())
        // MaintenanceModeFilter 必须放最前:维护期不浪费 rate limit / auth / DB 资源
        .addFilterBefore(maintenanceModeFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterAfter(consoleRateLimitFilter, MaintenanceModeFilter.class)
        .addFilterAfter(consoleAuthenticationFilter, ConsoleRateLimitFilter.class)
        .build();
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
}
