package com.example.batch.console.domain.job.application;

import com.example.batch.console.domain.governance.web.request.DeadLetterReplayRequest;
import com.example.batch.console.domain.job.web.request.BatchDayCatchUpRequest;
import com.example.batch.console.domain.job.web.request.CompensateRequest;
import com.example.batch.console.domain.job.web.request.CompensationCommandRequest;
import com.example.batch.console.domain.job.web.request.PartitionReplayRequest;
import com.example.batch.console.domain.job.web.request.RerunRequest;
import com.example.batch.console.domain.job.web.request.TaskReplayRequest;
import com.example.batch.console.domain.job.web.request.TriggerRequest;
import com.example.batch.console.domain.job.web.response.ConsoleBatchDayCatchUpResponse;
import com.example.batch.console.domain.ops.web.request.ConsoleCatchUpApprovalRequest;
import com.example.batch.console.web.request.job.CompensateRequest;
import com.example.batch.console.web.request.job.CompensationCommandRequest;
import com.example.batch.console.web.request.job.PartitionReplayRequest;
import com.example.batch.console.web.request.job.RerunRequest;
import com.example.batch.console.web.request.job.TaskReplayRequest;
import com.example.batch.console.web.request.job.TriggerRequest;
import com.example.batch.console.web.request.ops.BatchDayCatchUpRequest;
import com.example.batch.console.web.request.ops.ConsoleCatchUpApprovalRequest;
import com.example.batch.console.web.response.file.ConsoleBatchDayCatchUpResponse;
import java.util.List;
import java.util.Map;

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
  Map<String, Object> dryRunTrigger(TriggerRequest request);

  List<Map<String, Object>> batchTrigger(List<TriggerRequest> items, String idempotencyKey);
}
