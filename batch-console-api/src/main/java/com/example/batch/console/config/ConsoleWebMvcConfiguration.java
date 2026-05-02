package com.example.batch.console.config;

import com.example.batch.console.support.web.ConsoleIdempotencyInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 注册 {@link ConsoleIdempotencyInterceptor} 到 POST {@code /api/console/**} 路径。 */
@Configuration
@RequiredArgsConstructor
public class ConsoleWebMvcConfiguration implements WebMvcConfigurer {

  private final ConsoleIdempotencyInterceptor idempotencyInterceptor;

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(idempotencyInterceptor).addPathPatterns("/api/console/**");
  }
}
