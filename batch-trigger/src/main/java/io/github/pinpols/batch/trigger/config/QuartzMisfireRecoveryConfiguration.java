package io.github.pinpols.batch.trigger.config;

import io.github.pinpols.batch.trigger.infrastructure.QuartzMisfireRecoveryListener;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.context.annotation.Configuration;

/** 配置 Quartz misfire 的恢复与补偿行为。 */
@Configuration(proxyBeanMethods = false)
@Slf4j
@RequiredArgsConstructor
public class QuartzMisfireRecoveryConfiguration {

  private final Scheduler scheduler;

  @PostConstruct
  public void registerMisfireRecoveryListener() throws SchedulerException {
    scheduler
        .getListenerManager()
        .addTriggerListener(new QuartzMisfireRecoveryListener(() -> scheduler));
    log.info("Quartz misfire recovery listener registered");
  }
}
