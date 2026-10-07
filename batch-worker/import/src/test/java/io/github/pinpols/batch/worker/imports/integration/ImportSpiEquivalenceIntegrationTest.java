package io.github.pinpols.batch.worker.imports.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.spi.task.BatchTaskExecutor;
import io.github.pinpols.batch.common.spi.task.BatchTaskExecutorRegistry;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.testing.OrchestratorWireMockSupport;
import io.github.pinpols.batch.worker.imports.BatchWorkerImportApplication;
import io.github.pinpols.batch.worker.imports.infrastructure.ImportStepExecutionAdapter;
import io.github.pinpols.batch.worker.imports.infrastructure.ImportTaskExecutor;
import java.lang.reflect.Field;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * P0 Phase 3 等价性 IT — SPI 路径 ≡ @Primary 路径(import)。
 *
 * <p>验证 3 件事:
 *
 * <ol>
 *   <li>{@link BatchTaskExecutorRegistry} 在 import worker 上下文里含 "IMPORT" taskType
 *   <li>注册的 executor 实例就是 {@link ImportTaskExecutor}(SPI 包装)
 *   <li>包装内部的 delegate 跟 Spring @Primary 的 {@link ImportStepExecutionAdapter} bean 是同一实例 →
 *       证明两条路径**真的走同一份执行代码**,不是各自独立分支
 * </ol>
 *
 * <p>不真跑 pipeline 执行(那需要 stub job_definition + Kafka task fixtures),只验证 Spring 装配链路。 装配对了 = 行为等价(单测
 * ImportTaskExecutorTest 已覆盖 delegate 调用的入参翻译)。
 */
@SpringBootTest(
    classes = BatchWorkerImportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("导入 SPI 等价性集成测试:注册类型,执行器装配与委托实例同一性")
class ImportSpiEquivalenceIT extends AbstractIntegrationTest {

  @DynamicPropertySource
  static void orchestratorStub(DynamicPropertyRegistry registry) {
    OrchestratorWireMockSupport.registerOrchestratorBaseUrls(registry);
  }

  @Autowired
  ImportStepExecutionAdapter primaryAdapter;

  @Autowired
  BatchTaskExecutorRegistry registry;

  @Test
  @DisplayName("上下文加载后,任务执行器注册表包含导入类型")
  void shouldRegisterImportTaskType_whenContextLoads() {
    assertThat(registry.registeredTypes()).contains("IMPORT");
  }

  @Test
  @DisplayName("按导入类型查找时,返回导入任务执行器实例")
  void shouldReturnImportTaskExecutor_whenLookingUpByType() {
    BatchTaskExecutor exec = registry.find("IMPORT");
    assertThat(exec).isNotNull().isInstanceOf(ImportTaskExecutor.class);
  }

  @Test
  @DisplayName("包装执行器内部委托与主适配器为同一实例,证明两条路径共用实现")
  void shouldShareDelegateWithPrimaryAdapter_whenSpiWrapperBuilt() throws Exception {
    ImportTaskExecutor exec = (ImportTaskExecutor) registry.find("IMPORT");
    Field f = ImportTaskExecutor.class.getDeclaredField("delegate");
    f.setAccessible(true);
    Object delegate = f.get(exec);
    assertThat(delegate)
        .as("ImportTaskExecutor.delegate 必须是 Spring @Primary 注入的 ImportStepExecutionAdapter,"
            + "证明 SPI 路径跟老路径真共用一份业务代码(不是各自实现)")
        .isSameAs(primaryAdapter);
  }
}
