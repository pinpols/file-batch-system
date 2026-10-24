package io.github.pinpols.batch.orchestrator.infrastructure.archive;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.application.archive.OutboxArchiveService;
import io.github.pinpols.batch.orchestrator.application.archive.SuccessInstanceArchiveService;
import io.github.pinpols.batch.orchestrator.application.archive.WorkflowArchiveService;
import io.github.pinpols.batch.orchestrator.config.OutboxArchiveProperties;
import io.github.pinpols.batch.orchestrator.config.SuccessInstanceArchiveProperties;
import io.github.pinpols.batch.orchestrator.config.WorkflowArchiveProperties;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.infrastructure.metrics.ArchiveSchedulerHealthMetrics;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("归档调度健康上报,验证各调度器注册并在完整执行后记录成功")
class ArchiveSchedulerHealthReportingTest {

  @Test
  @DisplayName("Outbox 归档注册指标并在两个状态扫描完成后记录成功")
  void shouldReportOutboxArchiveHealth() {
    OutboxArchiveService service = mock(OutboxArchiveService.class);
    OrchestratorGracefulShutdown shutdown = mock(OrchestratorGracefulShutdown.class);
    ArchiveSchedulerHealthMetrics metrics = mock(ArchiveSchedulerHealthMetrics.class);
    when(service.archivePublished())
        .thenReturn(OutboxArchiveService.ArchiveBatchResult.empty(Instant.EPOCH));
    when(service.archiveGiveUp())
        .thenReturn(OutboxArchiveService.ArchiveBatchResult.empty(Instant.EPOCH));
    OutboxArchiveScheduler scheduler =
        new OutboxArchiveScheduler(service, new OutboxArchiveProperties(), shutdown, metrics);

    scheduler.registerHealthMetric();
    scheduler.archive();

    verify(metrics).register("outbox");
    verify(metrics).markSuccess("outbox");
  }

  @Test
  @DisplayName("作业实例归档注册指标并在扫描完成后记录成功")
  void shouldReportJobInstanceArchiveHealth() {
    SuccessInstanceArchiveService service = mock(SuccessInstanceArchiveService.class);
    OrchestratorGracefulShutdown shutdown = mock(OrchestratorGracefulShutdown.class);
    ArchiveSchedulerHealthMetrics metrics = mock(ArchiveSchedulerHealthMetrics.class);
    SuccessInstanceArchiveProperties properties = new SuccessInstanceArchiveProperties();
    properties.setBatchSize(1);
    SuccessInstanceArchiveService.ArchiveBatchResult fullBatch =
        new SuccessInstanceArchiveService.ArchiveBatchResult(true, Instant.EPOCH, 1, 1);
    when(service.archiveNextBatch())
        .thenReturn(
            fullBatch,
            fullBatch,
            SuccessInstanceArchiveService.ArchiveBatchResult.empty(Instant.EPOCH));
    SuccessInstanceArchiveScheduler scheduler =
        new SuccessInstanceArchiveScheduler(service, properties, shutdown, metrics);

    scheduler.registerHealthMetric();
    scheduler.archive();

    verify(metrics).register("job_instance");
    verify(metrics).markSuccess("job_instance");
    verify(service).reportIntegrityIssues();
    verify(service, times(3)).archiveNextBatch();
  }

  @Test
  @DisplayName("工作流归档注册指标并在扫描完成后记录成功")
  void shouldReportWorkflowArchiveHealth() {
    WorkflowArchiveService service = mock(WorkflowArchiveService.class);
    OrchestratorGracefulShutdown shutdown = mock(OrchestratorGracefulShutdown.class);
    ArchiveSchedulerHealthMetrics metrics = mock(ArchiveSchedulerHealthMetrics.class);
    when(service.archiveOnce())
        .thenReturn(WorkflowArchiveService.ArchiveBatchResult.empty(Instant.EPOCH));
    WorkflowArchiveScheduler scheduler =
        new WorkflowArchiveScheduler(service, new WorkflowArchiveProperties(), shutdown, metrics);

    scheduler.registerHealthMetric();
    scheduler.archive();

    verify(metrics).register("workflow");
    verify(metrics).markSuccess("workflow");
  }
}
