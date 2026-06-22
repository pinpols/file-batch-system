package com.example.batch.worker.processes.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.spi.task.BatchTaskExecutor;
import com.example.batch.common.spi.task.BatchTaskExecutorRegistry;
import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.testing.OrchestratorWireMockSupport;
import com.example.batch.worker.processes.BatchWorkerProcessApplication;
import com.example.batch.worker.processes.infrastructure.ProcessStepExecutionAdapter;
import com.example.batch.worker.processes.infrastructure.ProcessTaskExecutor;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** P0 Phase 3 等价性 IT — SPI 路径 ≡ @Primary 路径(process)。详见 {@code ImportSpiEquivalenceIT} 同名 doc。 */
@SpringBootTest(
    classes = BatchWorkerProcessApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ProcessSpiEquivalenceIT extends AbstractIntegrationTest {

  @DynamicPropertySource
  static void orchestratorStub(DynamicPropertyRegistry registry) {
    OrchestratorWireMockSupport.registerOrchestratorBaseUrls(registry);
  }

  @Autowired ProcessStepExecutionAdapter primaryAdapter;
  @Autowired BatchTaskExecutorRegistry registry;

  @Test
  void registryContainsProcessTaskType() {
    assertThat(registry.registeredTypes()).contains("PROCESS");
  }

  @Test
  void registryFindReturnsProcessTaskExecutor() {
    BatchTaskExecutor exec = registry.find("PROCESS");
    assertThat(exec).isNotNull().isInstanceOf(ProcessTaskExecutor.class);
  }

  @Test
  void wrapperDelegateIsSameAsPrimaryAdapter() throws Exception {
    ProcessTaskExecutor exec = (ProcessTaskExecutor) registry.find("PROCESS");
    Field f = ProcessTaskExecutor.class.getDeclaredField("delegate");
    f.setAccessible(true);
    Object delegate = f.get(exec);
    assertThat(delegate).isSameAs(primaryAdapter);
  }
}
