package io.github.pinpols.batch.orchestrator.application.service.task;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.application.service.replay.BatchDayReplayTerminalReconciler;
import io.github.pinpols.batch.orchestrator.application.service.version.ResultVersionWriter;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.observability.JobLifecycleMetricsRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 负责 job_instance 进入终态后的旁路收口。
 *
 * <p>终态本身由主服务通过状态机和 CAS 写入；写入成功后还必须同步安排生命周期指标、失败分类指标、子状态
 * 修复、结果版本和 replay 会话收口。这些动作都依赖同一个终态事实，但不负责决定终态，因此集中在本类。
 *
 * <p>调用方仍在原 report 事务中调用本类，且传入的分区快照在终态前已按原路径加载，避免改变查询时机和
 * result version 的聚合口径。
 */
@Component
final class TaskOutcomeTerminalFinalizer {

  private final JobLifecycleMetricsRecorder jobLifecycleMetricsRecorder;
  private final MeterRegistry meterRegistry;
  private final JobInstanceTerminalChildStateReconciler childStateReconciler;
  private final ResultVersionWriter resultVersionWriter;
  private final BatchDayReplayTerminalReconciler replayTerminalReconciler;

  TaskOutcomeTerminalFinalizer(
      JobLifecycleMetricsRecorder jobLifecycleMetricsRecorder,
      MeterRegistry meterRegistry,
      JobInstanceTerminalChildStateReconciler childStateReconciler,
      ResultVersionWriter resultVersionWriter,
      BatchDayReplayTerminalReconciler replayTerminalReconciler) {
    this.jobLifecycleMetricsRecorder = jobLifecycleMetricsRecorder;
    this.meterRegistry = meterRegistry;
    this.childStateReconciler = childStateReconciler;
    this.resultVersionWriter = resultVersionWriter;
    this.replayTerminalReconciler = replayTerminalReconciler;
  }

  void finalizeTerminal(
      JobInstanceEntity jobInstance,
      TaskOutcomeCommand command,
      String instanceStatus,
      Instant finishedAt,
      String instanceFailureClass,
      List<JobPartitionEntity> partitions) {
    jobLifecycleMetricsRecorder.recordCompletionAfterCommit(
        command.tenantId(), jobInstance.getId(), instanceStatus, finishedAt);
    if (EmptyChecks.isNotNull(instanceFailureClass)) {
      meterRegistry
          .counter(
              "batch.job.failure",
              "tenant",
              Optional.ofNullable(command.tenantId()).orElse("unknown"),
              "jobCode",
              Optional.ofNullable(jobInstance.getJobCode()).orElse("unknown"),
              "class",
              instanceFailureClass)
          .increment();
    }
    childStateReconciler.reconcile(command.tenantId(), jobInstance.getId(), instanceStatus);
    resultVersionWriter.writeOnTerminal(
        jobInstance,
        TaskOutcomeSummaryBuilder.aggregateSuccessfulPartitionOutputs(partitions, command));
    if (EmptyChecks.isNotNull(jobInstance.getReplaySessionId())) {
      replayTerminalReconciler.reconcileOnTerminal(
          jobInstance.getTenantId(),
          jobInstance.getReplaySessionId(),
          jobInstance.getJobCode(),
          jobInstance.getId(),
          instanceStatus);
    }
  }
}
