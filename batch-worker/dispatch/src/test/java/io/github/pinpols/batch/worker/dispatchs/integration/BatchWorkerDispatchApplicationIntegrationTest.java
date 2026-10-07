package io.github.pinpols.batch.worker.dispatchs.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.testing.OrchestratorWireMockSupport;
import io.github.pinpols.batch.worker.dispatchs.BatchWorkerDispatchApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    classes = BatchWorkerDispatchApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("分发 Worker 应用启动集成:在真实基础设施上完整装配 Spring 上下文")
class BatchWorkerDispatchApplicationIntegrationTest extends AbstractIntegrationTest {

  @DynamicPropertySource
  static void orchestratorStub(DynamicPropertyRegistry registry) {
    OrchestratorWireMockSupport.registerOrchestratorBaseUrls(registry);
  }

  @Autowired
  ApplicationContext applicationContext;

  @Test
  @DisplayName("应用上下文成功启动,容器实例可注入")
  void shouldLoadApplicationContext_whenStarted() {
    assertThat(applicationContext).isNotNull();
  }
}
