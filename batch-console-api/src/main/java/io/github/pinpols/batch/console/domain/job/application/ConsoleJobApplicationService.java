package io.github.pinpols.batch.console.domain.job.application;

import io.github.pinpols.batch.console.domain.job.view.DryRunTriggerResult;
import io.github.pinpols.batch.console.domain.job.web.request.BatchDayCatchUpRequest;
import io.github.pinpols.batch.console.domain.job.web.request.CompensateRequest;
import io.github.pinpols.batch.console.domain.job.web.response.ConsoleBatchDayCatchUpResponse;
import io.github.pinpols.batch.console.domain.job.web.response.ConsoleBatchTriggerEntryResponse;
import io.github.pinpols.batch.console.shared.command.CompensationCommandRequest;
import io.github.pinpols.batch.console.shared.command.ConsoleCatchUpApprovalRequest;
import io.github.pinpols.batch.console.shared.command.DeadLetterReplayRequest;
import io.github.pinpols.batch.console.shared.command.PartitionReplayRequest;
import io.github.pinpols.batch.console.shared.command.RerunRequest;
import io.github.pinpols.batch.console.shared.command.TaskReplayRequest;
import io.github.pinpols.batch.console.shared.command.TriggerRequest;
import java.util.List;

/** 控制台作业运维写操作，经 HTTP 调用编排器与触发器。 */
public interface ConsoleJobApplicationService {

  String trigger(TriggerRequest request, String idempotencyKey);

  String compensation(CompensationCommandRequest request, String idempotencyKey);

  String compensate(CompensateRequest request, String idempotencyKey);

  String rerun(RerunRequest request, String idempotencyKey);

  String replayDeadLetter(DeadLetterReplayRequest request, String idempotencyKey);

  String replayTask(TaskReplayRequest request, String idempotencyKey);

  String replayPartition(PartitionReplayRequest request, String idempotencyKey);

  String approveCatchUp(ConsoleCatchUpApprovalRequest request, String idempotencyKey);

  ConsoleBatchDayCatchUpResponse catchUpBatchDay(
      String bizDate, BatchDayCatchUpRequest request, String idempotencyKey);

  /** 只校验不触发。 */
  DryRunTriggerResult dryRunTrigger(TriggerRequest request);

  List<ConsoleBatchTriggerEntryResponse> batchTrigger(
      List<TriggerRequest> items, String idempotencyKey);
}
