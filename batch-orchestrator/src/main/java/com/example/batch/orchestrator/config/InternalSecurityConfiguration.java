package com.example.batch.orchestrator.config;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.orchestrator.auth.ApiKeyVerifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** 注册 {@link InternalAuthFilter} 到 {@code /internal/**} URL 模式。 */
@Configuration
@EnableAsync
public class InternalSecurityConfiguration {

  @Bean
  public FilterRegistrationBean<InternalAuthFilter> internalAuthFilter(
      BatchSecurityProperties securityProperties, ApiKeyVerifier apiKeyVerifier) {
    FilterRegistrationBean<InternalAuthFilter> registration = new FilterRegistrationBean<>();
    registration.setFilter(new InternalAuthFilter(securityProperties, apiKeyVerifier));
    registration.addUrlPatterns("/internal/*");
    registration.setOrder(1);
    return registration;
  }
}
