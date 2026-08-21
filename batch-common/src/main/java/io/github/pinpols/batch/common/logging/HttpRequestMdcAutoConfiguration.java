package io.github.pinpols.batch.common.logging;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.filter.OncePerRequestFilter;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(OncePerRequestFilter.class)
/** 为 HTTP 请求装配 trace、租户等 MDC 上下文。 */
public class HttpRequestMdcAutoConfiguration {

  @Bean
  public HttpRequestMdcFilter httpRequestMdcFilter() {
    return new HttpRequestMdcFilter();
  }
}
