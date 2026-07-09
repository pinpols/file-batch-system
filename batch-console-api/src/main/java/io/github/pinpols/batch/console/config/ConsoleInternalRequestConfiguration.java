package io.github.pinpols.batch.console.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 注册 {@link ConsoleInternalRequestSizeFilter} 到 {@code /internal/**},在鉴权/反序列化前拦超大体。 */
@Configuration(proxyBeanMethods = false)
public class ConsoleInternalRequestConfiguration {

  /** order=0:尽早在过滤链前段按 Content-Length 拦掉超大内部请求体,避免撑爆内存(参考 orchestrator 同款)。 */
  @Bean
  public FilterRegistrationBean<ConsoleInternalRequestSizeFilter> consoleInternalRequestSizeFilter(
      ConsoleInternalRequestProperties properties) {
    FilterRegistrationBean<ConsoleInternalRequestSizeFilter> registration =
        new FilterRegistrationBean<>();
    registration.setFilter(new ConsoleInternalRequestSizeFilter(properties));
    registration.addUrlPatterns("/internal/*");
    registration.setOrder(0);
    return registration;
  }
}
