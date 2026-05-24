package com.example.batch.worker.core.reportoutbox;

import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.worker.core.domain.TaskExecutionReport;
import com.example.batch.worker.core.infrastructure.OrchestratorReportHttpSubmitter;
import com.example.batch.worker.core.infrastructure.WorkerTaskLeaseRenewer;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;

@Slf4j
public class WorkerReportOutboxCoordinator {

  private final WorkerReportOutboxRepository repository;
  private final WorkerReportOutboxProperties props;
  private final OrchestratorReportHttpSubmitter httpSubmitter;
  private final ObjectProvider<MeterRegistry> meterRegistryProvider;
  private final ObjectProvider<WorkerTaskLeaseRenewer> leaseRenewerProvider;
  private final WorkerReportOutboxPollClaimer pollClaimer;

  public WorkerReportOutboxCoordinator(
      WorkerReportOutboxRepository repository,
      WorkerReportOutboxProperties props,
      @Lazy OrchestratorReportHttpSubmitter httpSubmitter,
      ObjectProvider<MeterRegistry> meterRegistryProvider,
      ObjectProvider<WorkerTaskLeaseRenewer> leaseRenewerProvider,
      WorkerReportOutboxPollClaimer pollClaimer) {
    this.repository = repository;
    this.props = props;
    this.httpSubmitter = httpSubmitter;
    this.meterRegistryProvider = meterRegistryProvider;
    this.leaseRenewerProvider = leaseRenewerProvider;
    this.pollClaimer = pollClaimer;
  }

  /** REPORT HTTP 链已全部失败后写入 outbox；返回 false 表示持久化失败，调用方应继续抛出原始异常。 */
  public boolean enqueue(TaskExecutionReport report) {
    try {
      repository.upsert(report);
      log.warn(
          "task report deferred to worker report outbox ({}): tenantId={}, taskId={}",
          props.getStorage(),
          report.getTenantId(),
          report.getTaskId());
      return true;
    } catch (RuntimeException ex) {
      log.error(
          "worker report outbox enqueue failed: tenantId={}, taskId={}",
          report.getTenantId(),
          report.getTaskId(),
          ex);
      return false;
    }
  }

  @Scheduled(fixedDelayString = "${batch.worker.report-outbox.poll-interval-millis:5000}")
  public void pollDeferredReports() {
    if (props.isPausePollWhenRenewCircuitOpen()) {
      WorkerTaskLeaseRenewer renewer = leaseRenewerProvider.getIfAvailable();
      if (renewer != null && renewer.isRenewCircuitOpen()) {
        log.debug("report outbox poll skipped: renew circuit OPEN");
        return;
      }
    }
    long now = BatchDateTimeSupport.utcEpochMillis();
    long deadline =
        props.getPollMaxDurationMillis() > 0
            ? now + props.getPollMaxDurationMillis()
            : Long.MAX_VALUE;
    int batch = Math.max(1, props.getPollBatchSize());
    for (int i = 0; i < batch; i++) {
      // P1: 总耗时熔断 — orchestrator 慢响应时把 batch 提前 break,
      // 避免单次调度长时间占用线程拖延下一调度周期和 recoverStalePublishing。
      // needs-manual-review: 完整修复(异步 CompletableFuture submit)待下一轮统筹,
      // 本批保守只做熔断 + 配置化上限。
      if (BatchDateTimeSupport.utcEpochMillis() >= deadline) {
        log.warn(
            "report outbox poll cut off by pollMaxDurationMillis={} after {} rows",
            props.getPollMaxDurationMillis(),
            i);
        break;
      }
      Optional<WorkerReportOutboxRow> claimed = pollClaimer.claimNext(now);
      if (claimed.isEmpty()) {
        break;
      }
      WorkerReportOutboxRow row = claimed.get();
      TaskExecutionReport report;
      try {
        report = repository.deserializePayload(row.payloadJson());
      } catch (RuntimeException ex) {
        log.error("worker report outbox corrupt payload: id={}", row.id(), ex);
        repository.markGiveUp(row.id(), "CORRUPT_PAYLOAD");
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry != null) {
          registry.counter("batch.worker.report.outbox.give_up.total").increment();
        }
        continue;
      }
      try {
        httpSubmitter.submitReportOverHttp(report);
        repository.delete(row.id());
      } catch (RuntimeException ex) {
        SwallowedExceptionLogger.warn(
            WorkerReportOutboxCoordinator.class, "catch:RuntimeException", ex);

        repository.recordFailure(row.id(), now, ex);
      }
    }
  }

  @Scheduled(
      fixedDelayString =
          "${batch.worker.report-outbox.stale-publishing-recover-interval-millis:60000}")
  public void recoverStalePublishing() {
    long cutoff =
        BatchDateTimeSupport.utcEpochMillis() - props.getPublishingStaleRecoverAfterMillis();
    int n = repository.resetStalePublishing(cutoff);
    if (n > 0) {
      log.warn("worker report outbox reset stale PUBLISHING rows: count={}", n);
    }
  }
}
