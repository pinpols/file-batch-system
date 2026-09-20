package io.github.pinpols.batch.trigger.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.startup.SecretPayloadFlywayCallback;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.trigger.BatchTriggerApplication;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestConstructor;

@SpringBootTest(
    classes = BatchTriggerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.flyway.enabled=false",
      "spring.quartz.job-store-type=jdbc",
      "spring.quartz.jdbc.initialize-schema=always"
    })
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class BatchTriggerApplicationIntegrationTest extends AbstractIntegrationTest {

  private final Scheduler scheduler;
  private final SecretPayloadFlywayCallback secretPayloadFlywayCallback;

  BatchTriggerApplicationIntegrationTest(
      Scheduler scheduler, SecretPayloadFlywayCallback secretPayloadFlywayCallback) {
    this.scheduler = scheduler;
    this.secretPayloadFlywayCallback = secretPayloadFlywayCallback;
  }

  @Test
  void contextLoads() {
    assertThat(scheduler).isNotNull();
    assertThat(secretPayloadFlywayCallback).isNotNull();
  }

  @Test
  void quartzSchedulerStarted() throws Exception {
    assertThat(scheduler.isStarted()).isTrue();
  }
}
