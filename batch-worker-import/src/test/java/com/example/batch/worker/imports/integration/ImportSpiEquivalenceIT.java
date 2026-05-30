package com.example.batch.worker.imports.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.spi.task.BatchTaskExecutor;
import com.example.batch.common.spi.task.BatchTaskExecutorRegistry;
import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.testing.OrchestratorWireMockSupport;
import com.example.batch.worker.imports.BatchWorkerImportApplication;
import com.example.batch.worker.imports.infrastructure.ImportStepExecutionAdapter;
import com.example.batch.worker.imports.infrastructure.ImportTaskExecutor;
import java.lang.reflect.Field;
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
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ImportSpiEquivalenceIT extends AbstractIntegrationTest {

  @DynamicPropertySource
  static void orchestratorStub(DynamicPropertyRegistry registry) {
    OrchestratorWireMockSupport.registerOrchestratorBaseUrls(registry);
  }

  @Autowired ImportStepExecutionAdapter primaryAdapter;
  @Autowired BatchTaskExecutorRegistry registry;

  @Test
  void registryContainsImportTaskType() {
    assertThat(registry.registeredTypes()).contains("IMPORT");
  }

  @Test
  void registryFindReturnsImportTaskExecutor() {
    BatchTaskExecutor exec = registry.find("IMPORT");
    assertThat(exec).isNotNull().isInstanceOf(ImportTaskExecutor.class);
  }

  @Test
  void wrapperDelegateIsSameAsPrimaryAdapter() throws Exception {
    ImportTaskExecutor exec = (ImportTaskExecutor) registry.find("IMPORT");
    Field f = ImportTaskExecutor.class.getDeclaredField("delegate");
    f.setAccessible(true);
    Object delegate = f.get(exec);
    assertThat(delegate)
        .as(
            "ImportTaskExecutor.delegate 必须是 Spring @Primary 注入的 ImportStepExecutionAdapter,"
                + "证明 SPI 路径跟老路径真共用一份业务代码(不是各自实现)")
        .isSameAs(primaryAdapter);
  }
}
