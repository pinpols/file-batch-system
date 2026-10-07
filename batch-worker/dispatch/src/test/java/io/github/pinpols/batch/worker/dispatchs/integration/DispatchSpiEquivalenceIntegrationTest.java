package io.github.pinpols.batch.worker.dispatchs.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.spi.task.BatchTaskExecutor;
import io.github.pinpols.batch.common.spi.task.BatchTaskExecutorRegistry;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.testing.OrchestratorWireMockSupport;
import io.github.pinpols.batch.worker.dispatchs.BatchWorkerDispatchApplication;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.DispatchStepExecutionAdapter;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.DispatchTaskExecutor;
import java.lang.reflect.Field;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** P0 Phase 3 等价性 IT — SPI 路径 ≡ @Primary 路径(dispatch)。详见 {@code ImportSpiEquivalenceIT} 同名 doc。 */
@SpringBootTest(
    classes = BatchWorkerDispatchApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("分发 SPI 等价性:注册表暴露分发任务类型,查得的执行器与主适配器共用同一实现")
class DispatchSpiEquivalenceIT extends AbstractIntegrationTest {

  @DynamicPropertySource
  static void orchestratorStub(DynamicPropertyRegistry registry) {
    OrchestratorWireMockSupport.registerOrchestratorBaseUrls(registry);
  }

  @Autowired
  DispatchStepExecutionAdapter primaryAdapter;

  @Autowired
  BatchTaskExecutorRegistry registry;

  @Test
  @DisplayName("注册表列出的已注册类型包含分发任务类型")
  void shouldContainDispatchTaskType_whenListingRegisteredTypes() {
    assertThat(registry.registeredTypes()).contains("DISPATCH");
  }

  @Test
  @DisplayName("按分发任务类型查找时,返回分发任务执行器实例")
  void shouldReturnDispatchTaskExecutor_whenFindingByType() {
    BatchTaskExecutor exec = registry.find("DISPATCH");
    assertThat(exec).isNotNull().isInstanceOf(DispatchTaskExecutor.class);
  }

  @Test
  @DisplayName("执行器内部持有的委派与主适配器是同一实例,SPI 与主适配器两条路径等价")
  void shouldShareSameDelegate_whenComparingExecutorWithPrimaryAdapter() throws Exception {
    DispatchTaskExecutor exec = (DispatchTaskExecutor) registry.find("DISPATCH");
    Field f = DispatchTaskExecutor.class.getDeclaredField("delegate");
    f.setAccessible(true);
    Object delegate = f.get(exec);
    assertThat(delegate).isSameAs(primaryAdapter);
  }
}
