package com.example.batch.worker.exports.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.spi.task.BatchTaskExecutor;
import com.example.batch.common.spi.task.BatchTaskExecutorRegistry;
import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.testing.OrchestratorWireMockSupport;
import com.example.batch.worker.exports.BatchWorkerExportApplication;
import com.example.batch.worker.exports.infrastructure.ExportStepExecutionAdapter;
import com.example.batch.worker.exports.infrastructure.ExportTaskExecutor;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** P0 Phase 3 等价性 IT — SPI 路径 ≡ @Primary 路径(export)。详见 {@code ImportSpiEquivalenceIT} 同名 doc。 */
@SpringBootTest(
    classes = BatchWorkerExportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ExportSpiEquivalenceIT extends AbstractIntegrationTest {

  @DynamicPropertySource
  static void orchestratorStub(DynamicPropertyRegistry registry) {
    OrchestratorWireMockSupport.registerOrchestratorBaseUrls(registry);
  }

  @Autowired ExportStepExecutionAdapter primaryAdapter;
  @Autowired BatchTaskExecutorRegistry registry;

  @Test
  void registryContainsExportTaskType() {
    assertThat(registry.registeredTypes()).contains("EXPORT");
  }

  @Test
  void registryFindReturnsExportTaskExecutor() {
    BatchTaskExecutor exec = registry.find("EXPORT");
    assertThat(exec).isNotNull().isInstanceOf(ExportTaskExecutor.class);
  }

  @Test
  void wrapperDelegateIsSameAsPrimaryAdapter() throws Exception {
    ExportTaskExecutor exec = (ExportTaskExecutor) registry.find("EXPORT");
    Field f = ExportTaskExecutor.class.getDeclaredField("delegate");
    f.setAccessible(true);
    Object delegate = f.get(exec);
    assertThat(delegate).isSameAs(primaryAdapter);
  }
}
