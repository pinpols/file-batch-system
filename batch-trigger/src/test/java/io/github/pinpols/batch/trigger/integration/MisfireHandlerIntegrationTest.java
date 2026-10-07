package io.github.pinpols.batch.trigger.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.trigger.BatchTriggerApplication;
import io.github.pinpols.batch.trigger.domain.MisfireHandler;
import io.github.pinpols.batch.trigger.infrastructure.QuartzLaunchJob;
import io.github.pinpols.batch.trigger.infrastructure.QuartzMisfireListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Wiring check for {@link QuartzMisfireListener}. Quartz cron triggers use {@code
 * MISFIRE_INSTRUCTION_DO_NOTHING} (scheduler-level skip); application catch-up vs scheduled-only is
 * covered in {@link io.github.pinpols.batch.trigger.infrastructure.QuartzLaunchJobTest}.
 */
@SpringBootTest(
    classes = BatchTriggerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("misfire 回调装配:QuartzMisfireListener 与 QuartzLaunchJob 在启动上下文中可用")
class MisfireHandlerIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  MisfireHandler misfireHandler;

  @Autowired
  QuartzLaunchJob quartzLaunchJob;

  @Test
  @DisplayName("MisfireHandler 由 QuartzMisfireListener 实现,确保 misfire 回调落到 Quartz 审计留痕")
  void shouldWireMisfireHandlerBean() {
    assertThat(misfireHandler).isInstanceOf(QuartzMisfireListener.class);
  }

  @Test
  @DisplayName("QuartzLaunchJob 作为 misfire 审计链路的依赖必须能被 Spring 成功装配")
  void shouldLoadQuartzLaunchJobForMisfireAuditPath() {
    assertThat(quartzLaunchJob).isNotNull();
  }

  @Test
  @DisplayName("对未知触发名的 misfire 回调只做审计留痕,处理过程不得抛出异常中断调度")
  void shouldNotThrowWhenHandlingMisfire() {
    assertThatCode(() -> misfireHandler.handle("t1:JOB_X")).doesNotThrowAnyException();
  }
}
