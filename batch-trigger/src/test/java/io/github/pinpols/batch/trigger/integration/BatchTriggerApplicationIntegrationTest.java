package io.github.pinpols.batch.trigger.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.startup.SecretPayloadFlywayCallback;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.trigger.BatchTriggerApplication;
import org.junit.jupiter.api.DisplayName;
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
@DisplayName("Trigger 应用启动装配:JDBC JobStore 模式下调度器与密钥 Flyway 回调随上下文就绪")
class BatchTriggerApplicationIntegrationTest extends AbstractIntegrationTest {

  private final Scheduler scheduler;
  private final SecretPayloadFlywayCallback secretPayloadFlywayCallback;

  BatchTriggerApplicationIntegrationTest(
      Scheduler scheduler, SecretPayloadFlywayCallback secretPayloadFlywayCallback) {
    this.scheduler = scheduler;
    this.secretPayloadFlywayCallback = secretPayloadFlywayCallback;
  }

  @Test
  @DisplayName("应用上下文加载后 Quartz 调度器与密钥 Flyway 回调均完成装配,不能为空")
  void contextLoads_succeeds() {
    assertThat(scheduler).isNotNull();
    assertThat(secretPayloadFlywayCallback).isNotNull();
  }

  @Test
  @DisplayName("启动完成后 Quartz 调度器处于 started 状态,可以接受触发注册与调度")
  void quartzScheduler_isStarted() throws Exception {
    assertThat(scheduler.isStarted()).isTrue();
  }
}
