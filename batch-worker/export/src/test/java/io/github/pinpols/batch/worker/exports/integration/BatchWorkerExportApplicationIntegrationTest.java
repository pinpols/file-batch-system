package io.github.pinpols.batch.worker.exports.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.testing.OrchestratorWireMockSupport;
import io.github.pinpols.batch.worker.exports.BatchWorkerExportApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    classes = BatchWorkerExportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("导出工作器应用集成测试:应用上下文可正常启动装配")
class BatchWorkerExportApplicationIntegrationTest extends AbstractIntegrationTest {

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
