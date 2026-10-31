package io.github.pinpols.batch.trigger.config;

import io.github.pinpols.batch.common.config.KafkaNamespaceProperties;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.trigger.infrastructure.QuartzMisfireRecoveryListener;
import io.github.pinpols.batch.trigger.service.ScheduledOccurrenceRecorder;
import org.quartz.Scheduler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.quartz.autoconfigure.SchedulerFactoryBeanCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;

/** 配置 Quartz misfire 的恢复与补偿行为。 */
@Configuration(proxyBeanMethods = false)
public class QuartzMisfireRecoveryConfiguration {

  @Bean
  QuartzMisfireRecoveryListener quartzMisfireRecoveryListener(
      ObjectProvider<Scheduler> schedulerProvider,
      @Qualifier("taskScheduler") TaskScheduler taskScheduler,
      ScheduledOccurrenceRecorder occurrenceRecorder,
      TriggerRuntimeProperties triggerRuntimeProperties) {
    return new QuartzMisfireRecoveryListener(
        schedulerProvider::getObject, taskScheduler, occurrenceRecorder, triggerRuntimeProperties);
  }

  @Bean
  SchedulerFactoryBeanCustomizer quartzMisfireRecoveryCustomizer(
      QuartzMisfireRecoveryListener misfireRecoveryListener,
      KafkaNamespaceProperties deploymentNamespace) {
    return factory -> {
      factory.setGlobalTriggerListeners(misfireRecoveryListener);
      factory.setSchedulerName(schedulerName(deploymentNamespace.getNamespace()));
    };
  }

  static String schedulerName(String deploymentNamespace) {
    if (EmptyChecks.isBlank(deploymentNamespace)) {
      return "BatchTriggerScheduler";
    }
    return "BatchTriggerScheduler-" + deploymentNamespace.trim();
  }
}
