package com.example.batch.trigger.wheel;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.boot.quartz.autoconfigure.SchedulerFactoryBeanCustomizer;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * QZ-prep-2 + QZ-rollback-1: Wheel↔Quartz 切换路径守护测试。
 *
 * <p>QuartzPauseWhenWheelEnabledCustomizer 是 wheel/quartz 互斥的执行点:
 *
 * <ul>
 *   <li>wheel 模式:Quartz `autoStartup=false`(挂着但不工作),wheel scheduler 接管 fire
 *   <li>quartz 模式:customizer 不动 Quartz(走 Spring Boot Quartz 默认 `autoStartup=true`)
 * </ul>
 */
class QuartzPauseWhenWheelEnabledCustomizerTest {

  @Test
  void shouldDisableQuartzAutoStartupWhenSchedulerImplIsWheel() {
    QuartzPauseWhenWheelEnabledCustomizer customizer = new QuartzPauseWhenWheelEnabledCustomizer();
    ReflectionTestUtils.setField(customizer, "schedulerImpl", "wheel");
    SchedulerFactoryBean factory = mock(SchedulerFactoryBean.class);

    SchedulerFactoryBeanCustomizer fn = customizer.pauseQuartzWhenWheelEnabled();
    fn.customize(factory);

    verify(factory).setAutoStartup(false);
  }

  @Test
  void shouldDisableQuartzAutoStartupCaseInsensitiveWHEEL() {
    QuartzPauseWhenWheelEnabledCustomizer customizer = new QuartzPauseWhenWheelEnabledCustomizer();
    ReflectionTestUtils.setField(customizer, "schedulerImpl", "WHEEL");
    SchedulerFactoryBean factory = mock(SchedulerFactoryBean.class);

    customizer.pauseQuartzWhenWheelEnabled().customize(factory);

    verify(factory).setAutoStartup(false);
  }

  @Test
  void shouldNotTouchQuartzWhenSchedulerImplIsQuartz_rollbackPath() {
    // QZ-rollback-1: 显式切 BATCH_TRIGGER_SCHEDULER_IMPL=quartz,customizer 必须不干预
    QuartzPauseWhenWheelEnabledCustomizer customizer = new QuartzPauseWhenWheelEnabledCustomizer();
    ReflectionTestUtils.setField(customizer, "schedulerImpl", "quartz");
    SchedulerFactoryBean factory = mock(SchedulerFactoryBean.class);

    customizer.pauseQuartzWhenWheelEnabled().customize(factory);

    verifyNoInteractions(factory);
  }

  @Test
  void shouldNotTouchQuartzWhenSchedulerImplIsUnknown() {
    // 未知值不应误关 Quartz(safer default)
    QuartzPauseWhenWheelEnabledCustomizer customizer = new QuartzPauseWhenWheelEnabledCustomizer();
    ReflectionTestUtils.setField(customizer, "schedulerImpl", "unknown-scheduler");
    SchedulerFactoryBean factory = mock(SchedulerFactoryBean.class);

    customizer.pauseQuartzWhenWheelEnabled().customize(factory);

    verifyNoInteractions(factory);
  }
}
