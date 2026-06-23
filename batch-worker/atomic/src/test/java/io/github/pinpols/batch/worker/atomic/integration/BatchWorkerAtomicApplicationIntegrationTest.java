package io.github.pinpols.batch.worker.atomic.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.testing.OrchestratorWireMockSupport;
import io.github.pinpols.batch.worker.atomic.BatchWorkerAtomicApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 专用原子任务 worker context-load 冒烟:验证 worker 能用真实 PG/Kafka(testcontainers)启动 —— consumer / loop /
 * route adapter / datasource / kafka 全部正确装配。executor 默认全关(@ConditionalOnProperty),本测 只验运行时骨架能起来。
 */
@SpringBootTest(
    classes = BatchWorkerAtomicApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BatchWorkerAtomicApplicationIntegrationTest extends AbstractIntegrationTest {

  @DynamicPropertySource
  static void orchestratorStub(DynamicPropertyRegistry registry) {
    OrchestratorWireMockSupport.registerOrchestratorBaseUrls(registry);
  }

  @Autowired ApplicationContext applicationContext;

  @Test
  void contextLoads() {
    assertThat(applicationContext).isNotNull();
  }
}
