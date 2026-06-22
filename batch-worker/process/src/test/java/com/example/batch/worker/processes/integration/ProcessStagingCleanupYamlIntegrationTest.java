package com.example.batch.worker.processes.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.worker.processes.BatchWorkerProcessApplication;
import com.example.batch.worker.processes.cleanup.ProcessStagingCleanupProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 守护 {@code batch-worker-process/src/test/resources/application-test.yml}：集成测常用 PG 未必有 {@code
 * batch.process_staging}， 须关闭孤儿清理调度，避免刷 {@code relation "batch.process_staging" does not exist}。
 */
@SpringBootTest(classes = BatchWorkerProcessApplication.class)
@ActiveProfiles("test")
class ProcessStagingCleanupYamlIntegrationTest extends AbstractIntegrationTest {

  @Autowired private ProcessStagingCleanupProperties processStagingCleanupProperties;

  @Test
  void applicationTestYamlDisablesStagingOrphanCleaner() {
    assertThat(processStagingCleanupProperties.isEnabled()).isFalse();
  }
}
