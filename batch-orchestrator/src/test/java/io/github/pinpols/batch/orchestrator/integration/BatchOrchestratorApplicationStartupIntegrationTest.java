package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.http.OutboundHttpTransport;
import io.github.pinpols.batch.common.startup.SecretPayloadFlywayCallback;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.infrastructure.http.OkHttpOrchestratorExternalHttpTransport;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestConstructor;

/**
 * 冒烟集成测试：使用真实 Postgres（平台 + 业务）、Kafka 和对象存储的 Spring 上下文。
 *
 * <p>继承 {@link AbstractIntegrationTest} —— 不要在此重复 {@code @BatchIntegrationTest} 或容器配置。
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@DisplayName("调度编排应用的启动冒烟,验证上下文可装配,外呼传输选用既定实现且数据库迁移回调已注册")
class BatchOrchestratorApplicationStartupIntegrationTest extends AbstractIntegrationTest {

  private final ApplicationContext applicationContext;
  private final Flyway flyway;

  BatchOrchestratorApplicationStartupIntegrationTest(
      ApplicationContext applicationContext, Flyway flyway) {
    this.applicationContext = applicationContext;
    this.flyway = flyway;
  }

  @Test
  @DisplayName("应用上下文可装配,外呼传输为既定实现且数据库迁移回调已注册")
  void shouldLoadContextAndRegisterMigrationCallback_whenApplicationStarts() {
    assertThat(applicationContext).isNotNull();
    assertThat(applicationContext.getBean(OutboundHttpTransport.class))
        .isInstanceOf(OkHttpOrchestratorExternalHttpTransport.class);
    assertThat(flyway.getConfiguration().getCallbacks())
        .anyMatch(SecretPayloadFlywayCallback.class::isInstance);
  }
}
