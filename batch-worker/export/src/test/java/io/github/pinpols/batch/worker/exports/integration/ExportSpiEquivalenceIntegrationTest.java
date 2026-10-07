package io.github.pinpols.batch.worker.exports.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.spi.task.BatchTaskExecutor;
import io.github.pinpols.batch.common.spi.task.BatchTaskExecutorRegistry;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.testing.OrchestratorWireMockSupport;
import io.github.pinpols.batch.worker.exports.BatchWorkerExportApplication;
import io.github.pinpols.batch.worker.exports.infrastructure.ExportStepExecutionAdapter;
import io.github.pinpols.batch.worker.exports.infrastructure.ExportTaskExecutor;
import java.lang.reflect.Field;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** P0 Phase 3 等价性 IT — SPI 路径 ≡ @Primary 路径(export)。详见 {@code ImportSpiEquivalenceIT} 同名 doc。 */
@SpringBootTest(
    classes = BatchWorkerExportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("导出 SPI 等价性集成测试:注册类型,执行器装配与委托实例同一性")
class ExportSpiEquivalenceIT extends AbstractIntegrationTest {

  @DynamicPropertySource
  static void orchestratorStub(DynamicPropertyRegistry registry) {
    OrchestratorWireMockSupport.registerOrchestratorBaseUrls(registry);
  }

  @Autowired
  ExportStepExecutionAdapter primaryAdapter;

  @Autowired
  BatchTaskExecutorRegistry registry;

  @Test
  @DisplayName("上下文加载后,任务执行器注册表包含导出类型")
  void shouldRegisterExportTaskType_whenContextLoads() {
    assertThat(registry.registeredTypes()).contains("EXPORT");
  }

  @Test
  @DisplayName("按导出类型查找时,返回导出任务执行器实例")
  void shouldReturnExportTaskExecutor_whenLookingUpByType() {
    BatchTaskExecutor exec = registry.find("EXPORT");
    assertThat(exec).isNotNull().isInstanceOf(ExportTaskExecutor.class);
  }

  @Test
  @DisplayName("包装执行器内部委托与主适配器为同一实例,证明两条路径共用实现")
  void shouldShareDelegateWithPrimaryAdapter_whenSpiWrapperBuilt() throws Exception {
    ExportTaskExecutor exec = (ExportTaskExecutor) registry.find("EXPORT");
    Field f = ExportTaskExecutor.class.getDeclaredField("delegate");
    f.setAccessible(true);
    Object delegate = f.get(exec);
    assertThat(delegate).isSameAs(primaryAdapter);
  }
}
