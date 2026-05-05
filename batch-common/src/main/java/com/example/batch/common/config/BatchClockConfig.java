package com.example.batch.common.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BatchClockConfig {

  /**
   * 技术时间统一使用 UTC Clock。
   *
   * <p>业务时区不在 Clock 里表达，而是在 BatchTimezoneProvider / BatchTimeSupport 中表达。
   */
  @Bean
  public Clock batchClock() {
    return Clock.systemUTC();
  }
}
