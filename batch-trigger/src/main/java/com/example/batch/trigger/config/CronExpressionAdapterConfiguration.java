package com.example.batch.trigger.config;

import com.example.batch.trigger.wheel.CronExpressionAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Quartz / wheel 均依赖 Cron 解析适配器；独立于 wheel conditional 装配,避免 Quartz IT 缺失 bean。 */
@Configuration
public class CronExpressionAdapterConfiguration {

  @Bean
  public CronExpressionAdapter cronExpressionAdapter() {
    return new CronExpressionAdapter();
  }
}
