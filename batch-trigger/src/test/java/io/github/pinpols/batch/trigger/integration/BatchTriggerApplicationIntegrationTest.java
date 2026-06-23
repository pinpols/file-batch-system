package io.github.pinpols.batch.trigger.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.trigger.BatchTriggerApplication;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    classes = BatchTriggerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.flyway.enabled=false",
      "spring.quartz.job-store-type=jdbc",
      "spring.quartz.jdbc.initialize-schema=always",
      // 2026-04-26 默认调度器切到 wheel，但本测试专门验证 Quartz 启动行为，强制 override
      "batch.trigger.scheduler-impl=quartz"
    })
class BatchTriggerApplicationIntegrationTest extends AbstractIntegrationTest {

  @Autowired Scheduler scheduler;

  @Test
  void contextLoads() {
    assertThat(scheduler).isNotNull();
  }

  @Test
  void quartzSchedulerStarted() throws Exception {
    assertThat(scheduler.isStarted()).isTrue();
  }
}
