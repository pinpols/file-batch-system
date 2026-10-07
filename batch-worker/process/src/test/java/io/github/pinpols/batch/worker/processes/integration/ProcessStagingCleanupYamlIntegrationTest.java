package io.github.pinpols.batch.worker.processes.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.testing.OrchestratorWireMockSupport;
import io.github.pinpols.batch.worker.processes.BatchWorkerProcessApplication;
import io.github.pinpols.batch.worker.processes.cleanup.ProcessStagingCleanupProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 守护 {@code batch-worker-process/src/test/resources/application-test.yml}：集成测常用 PG 未必有 {@code
 * batch.process_staging}， 须关闭孤儿清理调度，避免刷 {@code relation "batch.process_staging" does not exist}。
 */
@SpringBootTest(
    classes = BatchWorkerProcessApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("处理暂存清理测试配置:测试档位下关闭暂存孤儿清理调度,避免暂存表缺失时刷错误")
class ProcessStagingCleanupYamlIntegrationTest extends AbstractIntegrationTest {

  @DynamicPropertySource
  static void orchestratorStub(DynamicPropertyRegistry registry) {
    OrchestratorWireMockSupport.registerOrchestratorBaseUrls(registry);
  }

  @Autowired
  private ProcessStagingCleanupProperties processStagingCleanupProperties;

  @Test
  @DisplayName("测试档位下暂存孤儿清理开关为关闭,避免暂存表缺失时报错")
  void shouldDisableStagingOrphanCleaner_whenTestProfileActive() {
    assertThat(processStagingCleanupProperties.isEnabled()).isFalse();
  }
}
