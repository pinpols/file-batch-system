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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 校验 {@link QuartzMisfireListener} 的装配。Quartz cron 触发器使用 {@code
 * MISFIRE_INSTRUCTION_DO_NOTHING} 在调度器层面跳过错失触发；应用层补跑与仅按计划执行的区别由
 * {@link io.github.pinpols.batch.trigger.infrastructure.QuartzLaunchJobTest} 覆盖。
 */
@SpringBootTest(
    classes = BatchTriggerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ExtendWith(OutputCaptureExtension.class)
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
  void shouldNotThrowWhenHandlingMisfire(CapturedOutput output) {
    assertThatCode(() -> misfireHandler.handle("t1:JOB_X")).doesNotThrowAnyException();
    assertThat(output).contains("Quartz trigger misfire detected, triggerName=t1:JOB_X");
  }
}
