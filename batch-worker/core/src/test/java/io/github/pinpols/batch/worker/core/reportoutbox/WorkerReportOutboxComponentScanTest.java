package io.github.pinpols.batch.worker.core.reportoutbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.pinpols.batch.worker.core.infrastructure.OrchestratorReportHttpSubmitter;
import io.github.pinpols.batch.worker.core.mapper.WorkerReportOutboxPgMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.transaction.PlatformTransactionManager;

@DisplayName("回报发件箱组件扫描遵循启用开关")
class WorkerReportOutboxComponentScanTest {
  @TempDir
  Path temporaryDirectory;

  private ApplicationContextRunner runner(String storage) {
    ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(ScannedOutboxConfiguration.class)
        .withBean(WorkerReportOutboxPgMapper.class, () -> mock(WorkerReportOutboxPgMapper.class))
        .withBean(
            "transactionManager",
            PlatformTransactionManager.class,
            () -> mock(PlatformTransactionManager.class))
        .withBean(
            OrchestratorReportHttpSubmitter.class,
            () -> mock(OrchestratorReportHttpSubmitter.class))
        .withPropertyValues(
            "batch.worker.report-outbox.sqlite-path=" + temporaryDirectory.resolve("outbox.db"));
    if (!"missing".equals(storage)) {
      runner = runner.withPropertyValues("batch.worker.report-outbox.storage=" + storage);
    }
    return runner;
  }

  @ParameterizedTest(name = "开关 {0}、存储 {1} 不创建发件箱")
  @CsvSource({
    "false, PLATFORM_PG", "false, SQLITE", "false, missing",
    "missing, PLATFORM_PG", "missing, SQLITE", "missing, missing"
  })
  void disabledOrMissingDoesNotCreateOutbox(String enabled, String storage) {
    ApplicationContextRunner runner = runner(storage);
    if (!"missing".equals(enabled)) {
      runner = runner.withPropertyValues("batch.worker.report-outbox.enabled=" + enabled);
    }
    runner.run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context).doesNotHaveBean(WorkerReportOutboxCoordinator.class);
      assertThat(context).doesNotHaveBean(WorkerReportOutboxRepository.class);
      assertThat(temporaryDirectory.resolve("outbox.db")).doesNotExist();
    });
  }

  @ParameterizedTest(name = "开启后使用 {0} 存储")
  @CsvSource({"PLATFORM_PG", "SQLITE", "missing"})
  void enabledCreatesRequestedOutbox(String storage) {
    runner(storage)
        .withPropertyValues("batch.worker.report-outbox.enabled=true")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(WorkerReportOutboxCoordinator.class);
          assertThat(context).hasSingleBean(WorkerReportOutboxRepository.class);
          if ("SQLITE".equals(storage)) {
            assertThat(context.getBean(WorkerReportOutboxProperties.class).getStorage())
                .isEqualTo(WorkerReportOutboxStorage.SQLITE);
            assertThat(context).hasBean("workerReportOutboxDataSource");
            assertThat(temporaryDirectory.resolve("outbox.db")).exists();
          } else {
            assertThat(context.getBean(WorkerReportOutboxProperties.class).getStorage())
                .isEqualTo(WorkerReportOutboxStorage.PLATFORM_PG);
            assertThat(context).doesNotHaveBean("workerReportOutboxDataSource");
            assertThat(temporaryDirectory.resolve("outbox.db")).doesNotExist();
          }
        });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(WorkerReportOutboxProperties.class)
  @ComponentScan(
      basePackageClasses = WorkerReportOutboxConfiguration.class,
      useDefaultFilters = false,
      includeFilters =
          @ComponentScan.Filter(
              type = FilterType.ASSIGNABLE_TYPE,
              classes = {
                WorkerReportOutboxConfiguration.class,
                WorkerReportOutboxConfiguration.PlatformPgWorkerReportOutboxConfiguration.class,
                WorkerReportOutboxConfiguration.SqliteWorkerReportOutboxConfiguration.class
              }))
  static class ScannedOutboxConfiguration {}
}
