package com.example.batch.console.config;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Console-API CORS 配置。
 *
 * <p>仅当 {@code batch.console.security.cors-allowed-origins} 非空时生效（同域反代部署下空列表 → 不发 CORS 头, 浏览器同源策略
 * 自动保护）。跨域部署 SPA 时必须在 helm values 显式列 origin。
 *
 * <p>{@code allowCredentials=true} 让浏览器带上 HttpOnly cookie；W3C 规范禁止配 {@code *} origin + credentials
 * 同 时存在,因此本配置只接受具体 origin 列表。
 */
@Configuration
@RequiredArgsConstructor
public class ConsoleCorsConfiguration {

  private final ConsoleSecurityProperties properties;

  @Bean
  public CorsConfigurationSource consoleCorsConfigurationSource() {
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    List<String> origins = properties.getCorsAllowedOrigins();
    if (origins == null || origins.isEmpty()) {
      // 同域部署:不发 CORS 头,浏览器同源策略生效。返回空 source,DispatcherServlet 不应用 CORS。
      return source;
    }
    CorsConfiguration cfg = new CorsConfiguration();
    cfg.setAllowedOrigins(origins);
    cfg.setAllowedMethods(
        List.of(
            HttpMethod.GET.name(),
            HttpMethod.POST.name(),
            HttpMethod.PUT.name(),
            HttpMethod.PATCH.name(),
            HttpMethod.DELETE.name(),
            HttpMethod.OPTIONS.name()));
    cfg.setAllowedHeaders(
        List.of(
            HttpHeaders.AUTHORIZATION,
            HttpHeaders.CONTENT_TYPE,
            HttpHeaders.ACCEPT,
            "X-Tenant-Id",
            "X-Idempotency-Key",
            "X-Request-Id"));
    cfg.setExposedHeaders(List.of("X-Request-Id", "X-Trace-Id"));
    cfg.setAllowCredentials(true); // HttpOnly cookie 必需
    cfg.setMaxAge(3600L); // preflight 缓存 1h
    source.registerCorsConfiguration("/api/console/**", cfg);
    return source;
  }
}
