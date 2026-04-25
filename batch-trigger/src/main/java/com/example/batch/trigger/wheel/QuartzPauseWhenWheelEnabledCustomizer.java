package com.example.batch.trigger.wheel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.quartz.autoconfigure.SchedulerFactoryBeanCustomizer;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;

/**
 * 当 {@code batch.trigger.scheduler-impl=wheel} 时,把 Quartz Scheduler 的 autoStartup 设为
 * false,让 Quartz 不 fire 任何 trigger。wheel 与 quartz 严格互斥(详见
 * quartz-replacement-design.md §11)。
 *
 * <p>Quartz Bean 仍会被创建(给 TriggerSchedulerFacade / TriggerReconciler 引用,避免编译期
 * 大改造),但 Scheduler.start() 不会触发 — 等于"挂着但不工作"。
 *
 * <p>切回 quartz 模式(wheel → quartz):
 *
 * <ol>
 *   <li>set BATCH_TRIGGER_SCHEDULER_IMPL=quartz
 *   <li>重启 trigger
 *   <li>本 customizer 不生效,Quartz 走默认 autoStartup=true 重新接管
 * </ol>
 */
@Slf4j
@Configuration
public class QuartzPauseWhenWheelEnabledCustomizer {

  @Value("${batch.trigger.scheduler-impl:quartz}")
  private String schedulerImpl;

  @org.springframework.context.annotation.Bean
  public SchedulerFactoryBeanCustomizer pauseQuartzWhenWheelEnabled() {
    return (SchedulerFactoryBean factory) -> {
      if ("wheel".equalsIgnoreCase(schedulerImpl)) {
        factory.setAutoStartup(false);
        log.warn(
            "scheduler-impl=wheel — Quartz Scheduler autoStartup disabled; wheel scheduler is the"
                + " active fire engine. To revert: set BATCH_TRIGGER_SCHEDULER_IMPL=quartz and"
                + " restart trigger.");
      }
    };
  }
}
