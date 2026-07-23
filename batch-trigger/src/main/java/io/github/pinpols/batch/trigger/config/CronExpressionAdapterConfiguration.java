package io.github.pinpols.batch.trigger.config;

import io.github.pinpols.batch.trigger.support.CronExpressionAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Quartz 注册和业务日期推导共用的 Cron 解析适配器。 */
@Configuration
public class CronExpressionAdapterConfiguration {

  @Bean
  public CronExpressionAdapter cronExpressionAdapter() {
    return new CronExpressionAdapter();
  }
}
