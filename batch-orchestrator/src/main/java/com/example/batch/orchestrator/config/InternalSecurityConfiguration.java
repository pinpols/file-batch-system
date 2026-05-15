package com.example.batch.orchestrator.config;

import com.example.batch.common.config.BatchSecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 注册 {@link InternalAuthFilter} 到 {@code /internal/**} URL 模式。 */
@Configuration
public class InternalSecurityConfiguration {

  @Bean
  public FilterRegistrationBean<InternalAuthFilter> internalAuthFilter(
      BatchSecurityProperties securityProperties) {
    FilterRegistrationBean<InternalAuthFilter> registration = new FilterRegistrationBean<>();
    registration.setFilter(new InternalAuthFilter(securityProperties));
    // R4-P0-1：Servlet 路径映射 `/internal/*` 按规范匹配所有 `/internal/` 开头路径（含多段）。
    // 即便如此，filter 内部又叠了 startsWith("/internal/") 兜底；构成双层防御。
    registration.addUrlPatterns("/internal/*");
    registration.setOrder(1);
    return registration;
  }
}
