package io.github.pinpols.batch.worker.imports.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.testing.OrchestratorWireMockSupport;
import io.github.pinpols.batch.worker.imports.BatchWorkerImportApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 使用 Testcontainers Postgres（platform + biz）、Kafka、对象存储和模拟的 orchestrator HTTP 端点加载导入 Worker。 */
@SpringBootTest(
    classes = BatchWorkerImportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("导入工作器应用集成测试:容器化依赖下应用上下文可正常启动装配")
class BatchWorkerImportApplicationIntegrationTest extends AbstractIntegrationTest {

  @DynamicPropertySource
  static void orchestratorStub(DynamicPropertyRegistry registry) {
    OrchestratorWireMockSupport.registerOrchestratorBaseUrls(registry);
  }

  @Autowired
  ApplicationContext applicationContext;

  @Test
  @DisplayName("应用启动后上下文装配完成,不为空")
  void shouldLoadContext_whenApplicationStarts() {
    assertThat(applicationContext).isNotNull();
  }
}
