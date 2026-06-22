package com.example.batch.worker.dispatchs.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.spi.task.BatchTaskExecutor;
import com.example.batch.common.spi.task.BatchTaskExecutorRegistry;
import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.testing.OrchestratorWireMockSupport;
import com.example.batch.worker.dispatchs.BatchWorkerDispatchApplication;
import com.example.batch.worker.dispatchs.infrastructure.DispatchStepExecutionAdapter;
import com.example.batch.worker.dispatchs.infrastructure.DispatchTaskExecutor;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** P0 Phase 3 等价性 IT — SPI 路径 ≡ @Primary 路径(dispatch)。详见 {@code ImportSpiEquivalenceIT} 同名 doc。 */
@SpringBootTest(
    classes = BatchWorkerDispatchApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DispatchSpiEquivalenceIT extends AbstractIntegrationTest {

  @DynamicPropertySource
  static void orchestratorStub(DynamicPropertyRegistry registry) {
    OrchestratorWireMockSupport.registerOrchestratorBaseUrls(registry);
  }

  @Autowired DispatchStepExecutionAdapter primaryAdapter;
  @Autowired BatchTaskExecutorRegistry registry;

  @Test
  void registryContainsDispatchTaskType() {
    assertThat(registry.registeredTypes()).contains("DISPATCH");
  }

  @Test
  void registryFindReturnsDispatchTaskExecutor() {
    BatchTaskExecutor exec = registry.find("DISPATCH");
    assertThat(exec).isNotNull().isInstanceOf(DispatchTaskExecutor.class);
  }

  @Test
  void wrapperDelegateIsSameAsPrimaryAdapter() throws Exception {
    DispatchTaskExecutor exec = (DispatchTaskExecutor) registry.find("DISPATCH");
    Field f = DispatchTaskExecutor.class.getDeclaredField("delegate");
    f.setAccessible(true);
    Object delegate = f.get(exec);
    assertThat(delegate).isSameAs(primaryAdapter);
  }
}
