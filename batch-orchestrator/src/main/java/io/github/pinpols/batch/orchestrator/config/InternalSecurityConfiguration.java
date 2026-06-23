package io.github.pinpols.batch.orchestrator.config;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.orchestrator.auth.ApiKeyVerifier;
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

  /** 缺口③:内部端点请求体大小上限过滤器,order=0 排在鉴权前,让超大体在鉴权前即被廉价拦掉。 */
  @Bean
  public FilterRegistrationBean<InternalRequestSizeFilter> internalRequestSizeFilter(
      InternalRequestProperties internalRequestProperties) {
    FilterRegistrationBean<InternalRequestSizeFilter> registration = new FilterRegistrationBean<>();
    registration.setFilter(new InternalRequestSizeFilter(internalRequestProperties));
    registration.addUrlPatterns("/internal/*");
    registration.setOrder(0);
    return registration;
  }
}
