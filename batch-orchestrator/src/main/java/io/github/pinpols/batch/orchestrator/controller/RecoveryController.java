package io.github.pinpols.batch.orchestrator.controller;

import io.github.pinpols.batch.common.utils.IdGenerator;
import io.github.pinpols.batch.orchestrator.application.engine.OutboxEventKeyGenerator;
import io.github.pinpols.batch.orchestrator.application.service.governance.RetryGovernanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/recoveries")
@RequiredArgsConstructor
public class RecoveryController {

  private final RetryGovernanceService retryGovernanceService;

  /**
   * 任务重放（按 job_task 粒度重排队）。
   *
   * <p>该操作会把任务/step 重置到可 dispatch 的状态，并写入 dispatch outbox。
   */
  @PostMapping("/tasks/{taskId}/replay")
  public RecoveryResponse replayTask(
      @PathVariable Long taskId, @RequestBody TaskReplayRequest request) {
    String tenantId = request == null ? null : request.tenantId();
    String eventKey = OutboxEventKeyGenerator.forManualReplay(tenantId, "task", taskId);
    retryGovernanceService.retryTask(tenantId, taskId, eventKey);
    return new RecoveryResponse(IdGenerator.newBusinessNo("rpl"));
  }

  /**
   * 分区重放（按 job_partition 粒度重排队）。
   *
   * <p>该操作会把 partition 及其任务重置到可 dispatch 的状态，并写入 dispatch outbox。
   */
  @PostMapping("/partitions/{partitionId}/replay")
  public RecoveryResponse replayPartition(
      @PathVariable Long partitionId, @RequestBody PartitionReplayRequest request) {
    String tenantId = request == null ? null : request.tenantId();
    String eventKey = OutboxEventKeyGenerator.forManualReplay(tenantId, "partition", partitionId);
    retryGovernanceService.retryPartition(tenantId, partitionId, eventKey);
    return new RecoveryResponse(IdGenerator.newBusinessNo("rpl"));
  }

  public record RecoveryResponse(String operationNo) {}

  public record TaskReplayRequest(String tenantId) {}

  public record PartitionReplayRequest(String tenantId) {}
}
