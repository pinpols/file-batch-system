package com.example.batch.orchestrator.application.service.task;

import com.example.batch.common.enums.DictEnum;
import com.example.batch.common.enums.JobInstanceStatus;
import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** job_instance 写入终态时，把仍为非终态的 {@code job_partition}/{@code job_task} 收口到一致终态，防止节流按活跃分区计数时出现配额泄漏。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobInstanceTerminalChildStateReconciler {

  private final JobPartitionMapper jobPartitionMapper;
  private final JobTaskMapper jobTaskMapper;

  /**
   * @param terminalInstanceStatus {@link JobInstanceStatus} 的 code；非业务终态则 no-op。
   */
  public void reconcile(String tenantId, Long jobInstanceId, String terminalInstanceStatus) {
    Targets targets = resolveTargets(terminalInstanceStatus);
    if (targets == null) {
      return;
    }
    int partitions =
        jobPartitionMapper.closeNonTerminalPartitionsForTerminalInstance(
            tenantId, jobInstanceId, targets.partitionStatus());
    int tasks =
        jobTaskMapper.closeNonTerminalTasksForTerminalInstance(
            tenantId, jobInstanceId, targets.taskStatus());
    if (partitions > 0 || tasks > 0) {
      log.info(
          "job_instance terminal child-state reconcile: tenantId={} jobInstanceId={}"
              + " instanceStatus={} partitionsClosed={} tasksClosed={}",
          tenantId,
          jobInstanceId,
          terminalInstanceStatus,
          partitions,
          tasks);
    }
  }

  private Targets resolveTargets(String instanceStatusCode) {
    JobInstanceStatus st = DictEnum.fromCode(JobInstanceStatus.class, instanceStatusCode);
    if (st == null) {
      return null;
    }
    return switch (st) {
      case SUCCESS -> new Targets(PartitionStatus.SUCCESS.code(), TaskStatus.SUCCESS.code());
      case FAILED, PARTIAL_FAILED ->
          new Targets(PartitionStatus.FAILED.code(), TaskStatus.FAILED.code());
      case CANCELLED -> new Targets(PartitionStatus.CANCELLED.code(), TaskStatus.CANCELLED.code());
      case TERMINATED ->
          new Targets(PartitionStatus.TERMINATED.code(), TaskStatus.TERMINATED.code());
      default -> null;
    };
  }

  private record Targets(String partitionStatus, String taskStatus) {}
}
