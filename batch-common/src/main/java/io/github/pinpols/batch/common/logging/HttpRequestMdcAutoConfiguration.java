package io.github.pinpols.batch.common.logging;

import io.github.pinpols.batch.common.config.ApplicationNameProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.filter.OncePerRequestFilter;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(OncePerRequestFilter.class)
/** 为 HTTP 请求装配 trace、租户等 MDC 上下文。 */
public class HttpRequestMdcAutoConfiguration {

  // 本类经 auto-configuration imports 装配,测试切片可能不做组件扫描,故用静态 resolve 而非注入
  // ApplicationNameProvider bean。
  @Bean
  public HttpRequestMdcFilter httpRequestMdcFilter(Environment environment) {
    return new HttpRequestMdcFilter(ApplicationNameProvider.resolve(environment, "batch"));
  }
}
