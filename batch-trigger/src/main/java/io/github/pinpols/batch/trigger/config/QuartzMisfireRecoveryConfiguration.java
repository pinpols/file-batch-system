package io.github.pinpols.batch.trigger.config;

import io.github.pinpols.batch.trigger.infrastructure.QuartzMisfireRecoveryListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.quartz.autoconfigure.SchedulerFactoryBeanCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QuartzMisfireRecoveryConfiguration {

  @Bean
  SchedulerFactoryBeanCustomizer quartzMisfireRecoveryCustomizer(
      ObjectProvider<org.quartz.Scheduler> schedulerProvider) {
    return factory ->
        factory.setGlobalTriggerListeners(
            new QuartzMisfireRecoveryListener(schedulerProvider::getObject));
  }
}
