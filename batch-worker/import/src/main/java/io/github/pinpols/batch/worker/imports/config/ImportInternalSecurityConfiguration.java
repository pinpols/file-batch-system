package io.github.pinpols.batch.worker.imports.config;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.worker.imports.web.ImportInternalAuthFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 注册 {@link ImportInternalAuthFilter} 到 import worker 的 {@code /internal/**} URL 模式。 */
@Configuration
public class ImportInternalSecurityConfiguration {

  @Bean
  public FilterRegistrationBean<ImportInternalAuthFilter> importInternalAuthFilter(
      BatchSecurityProperties securityProperties) {
    FilterRegistrationBean<ImportInternalAuthFilter> registration = new FilterRegistrationBean<>();
    registration.setFilter(new ImportInternalAuthFilter(securityProperties));
    registration.addUrlPatterns("/internal/*");
    registration.setOrder(1);
    return registration;
  }
}
