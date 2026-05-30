package com.example.batch.console.domain.job.infrastructure;

import com.example.batch.console.domain.governance.web.request.DeadLetterReplayRequest;
import com.example.batch.console.web.request.ops.ConsoleCatchUpApprovalRequest;
import com.example.batch.console.domain.job.application.ConsoleJobApplicationService;
import com.example.batch.console.domain.job.application.ConsoleJobApprovalService;
import com.example.batch.console.domain.job.application.ConsoleJobRecoveryService;
import com.example.batch.console.domain.job.application.ConsoleJobTriggerService;
import com.example.batch.console.domain.job.web.request.BatchDayCatchUpRequest;
import com.example.batch.console.domain.job.web.request.CompensateRequest;
import com.example.batch.console.domain.job.web.request.CompensationCommandRequest;
import com.example.batch.console.domain.job.web.request.PartitionReplayRequest;
import com.example.batch.console.domain.job.web.request.RerunRequest;
import com.example.batch.console.domain.job.web.request.TaskReplayRequest;
import com.example.batch.console.domain.job.web.request.TriggerRequest;
import com.example.batch.console.domain.job.web.response.ConsoleBatchDayCatchUpResponse;
import com.example.batch.console.web.request.ops.ConsoleCatchUpApprovalRequest;
import com.example.batch.console.web.request.job.CompensateRequest;
import com.example.batch.console.web.request.job.CompensationCommandRequest;
import com.example.batch.console.web.request.job.PartitionReplayRequest;
import com.example.batch.console.web.request.job.RerunRequest;
import com.example.batch.console.web.request.job.TaskReplayRequest;
import com.example.batch.console.web.request.job.TriggerRequest;
import com.example.batch.console.web.request.ops.BatchDayCatchUpRequest;
import com.example.batch.console.domain.ops.web.request.ConsoleCatchUpApprovalRequest;
import com.example.batch.console.web.response.file.ConsoleBatchDayCatchUpResponse;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * {@link ConsoleJobApplicationService} 的默认实现：将各责任域委派给专门的服务。
 *
 * <p>保留此门面以保持 {@link ConsoleJobApplicationService} 接口对现有调用方（如审批服务）的兼容性。
 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleJobApplicationService implements ConsoleJobApplicationService {

  private final ConsoleJobTriggerService triggerService;
  private final ConsoleJobRecoveryService recoveryService;
  private final ConsoleJobApprovalService approvalService;

  @Override
  public String trigger(TriggerRequest request, String idempotencyKey) {
    return triggerService.trigger(request, idempotencyKey);
  }

  @Override
  public Map<String, Object> dryRunTrigger(TriggerRequest request) {
    return triggerService.dryRunTrigger(request);
  }

  @Override
  public List<Map<String, Object>> batchTrigger(List<TriggerRequest> items, String idempotencyKey) {
    return triggerService.batchTrigger(items, idempotencyKey);
  }

  @Override
  public String compensation(CompensationCommandRequest request, String idempotencyKey) {
    return recoveryService.compensation(request, idempotencyKey);
  }

  @Override
  public String compensate(CompensateRequest request, String idempotencyKey) {
    return recoveryService.compensate(request, idempotencyKey);
  }

  @Override
  public String rerun(RerunRequest request, String idempotencyKey) {
    return recoveryService.rerun(request, idempotencyKey);
  }

  @Override
  public String replayDeadLetter(DeadLetterReplayRequest request, String idempotencyKey) {
    return recoveryService.replayDeadLetter(request, idempotencyKey);
  }

  @Override
  public String replayTask(TaskReplayRequest request, String idempotencyKey) {
    return recoveryService.replayTask(request, idempotencyKey);
  }

  @Override
  public String replayPartition(PartitionReplayRequest request, String idempotencyKey) {
    return recoveryService.replayPartition(request, idempotencyKey);
  }

  @Override
  public String approveCatchUp(ConsoleCatchUpApprovalRequest request, String idempotencyKey) {
    return approvalService.approveCatchUp(request, idempotencyKey);
  }

  @Override
  public ConsoleBatchDayCatchUpResponse catchUpBatchDay(
      String bizDate, BatchDayCatchUpRequest request, String idempotencyKey) {
    return approvalService.catchUpBatchDay(bizDate, request, idempotencyKey);
  }
}
