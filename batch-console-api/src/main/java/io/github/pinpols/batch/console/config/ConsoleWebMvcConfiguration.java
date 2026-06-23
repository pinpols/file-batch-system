package io.github.pinpols.batch.console.config;

import io.github.pinpols.batch.console.support.web.ConsoleIdempotencyInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 注册 {@link ConsoleIdempotencyInterceptor} 到 Console API 路径；拦截器内部只处理写方法。 */
@Configuration
@RequiredArgsConstructor
public class ConsoleWebMvcConfiguration implements WebMvcConfigurer {

  private final ConsoleIdempotencyInterceptor idempotencyInterceptor;

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(idempotencyInterceptor).addPathPatterns("/api/console/**");
  }
}
