package io.github.pinpols.batch.worker.processes.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.spi.task.BatchTaskExecutor;
import io.github.pinpols.batch.common.spi.task.BatchTaskExecutorRegistry;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.testing.OrchestratorWireMockSupport;
import io.github.pinpols.batch.worker.processes.BatchWorkerProcessApplication;
import io.github.pinpols.batch.worker.processes.infrastructure.ProcessStepExecutionAdapter;
import io.github.pinpols.batch.worker.processes.infrastructure.ProcessTaskExecutor;
import java.lang.reflect.Field;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** P0 Phase 3 等价性 IT — SPI 路径 ≡ @Primary 路径(process)。详见 {@code ImportSpiEquivalenceIT} 同名 doc。 */
@SpringBootTest(
    classes = BatchWorkerProcessApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("处理 SPI 等价性:注册表暴露处理任务类型,查得的执行器与主适配器共用同一实现")
class ProcessSpiEquivalenceIT extends AbstractIntegrationTest {

  @DynamicPropertySource
  static void orchestratorStub(DynamicPropertyRegistry registry) {
    OrchestratorWireMockSupport.registerOrchestratorBaseUrls(registry);
  }

  @Autowired
  ProcessStepExecutionAdapter primaryAdapter;

  @Autowired
  BatchTaskExecutorRegistry registry;

  @Test
  @DisplayName("注册表列出的已注册类型包含处理任务类型")
  void shouldContainProcessTaskType_whenListingRegisteredTypes() {
    assertThat(registry.registeredTypes()).contains("PROCESS");
  }

  @Test
  @DisplayName("按处理任务类型查找时,返回处理任务执行器实例")
  void shouldReturnProcessTaskExecutor_whenFindingByType() {
    BatchTaskExecutor exec = registry.find("PROCESS");
    assertThat(exec).isNotNull().isInstanceOf(ProcessTaskExecutor.class);
  }

  @Test
  @DisplayName("执行器内部持有的委派与主适配器是同一实例,SPI 与主适配器两条路径等价")
  void shouldShareSameDelegate_whenComparingExecutorWithPrimaryAdapter() throws Exception {
    ProcessTaskExecutor exec = (ProcessTaskExecutor) registry.find("PROCESS");
    Field f = ProcessTaskExecutor.class.getDeclaredField("delegate");
    f.setAccessible(true);
    Object delegate = f.get(exec);
    assertThat(delegate).isSameAs(primaryAdapter);
  }
}
