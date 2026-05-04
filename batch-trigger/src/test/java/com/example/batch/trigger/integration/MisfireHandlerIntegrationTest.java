package com.example.batch.trigger.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.trigger.BatchTriggerApplication;
import com.example.batch.trigger.domain.MisfireHandler;
import com.example.batch.trigger.infrastructure.QuartzLaunchJob;
import com.example.batch.trigger.infrastructure.QuartzMisfireListener;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Wiring check for {@link QuartzMisfireListener}. Quartz cron triggers use {@code
 * MISFIRE_INSTRUCTION_DO_NOTHING} (scheduler-level skip); application catch-up vs scheduled-only is
 * covered in {@link com.example.batch.trigger.infrastructure.QuartzLaunchJobTest}.
 */
@SpringBootTest(
    classes = BatchTriggerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MisfireHandlerIntegrationTest extends AbstractIntegrationTest {

  @Autowired MisfireHandler misfireHandler;

  @Autowired QuartzLaunchJob quartzLaunchJob;

  @Test
  void shouldWireMisfireHandlerBean() {
    assertThat(misfireHandler).isInstanceOf(QuartzMisfireListener.class);
  }

  @Test
  void shouldLoadQuartzLaunchJobForMisfireAuditPath() {
    assertThat(quartzLaunchJob).isNotNull();
  }

  @Test
  void shouldNotThrowWhenHandlingMisfire() {
    assertThatCode(() -> misfireHandler.handle("t1:JOB_X")).doesNotThrowAnyException();
  }
}
