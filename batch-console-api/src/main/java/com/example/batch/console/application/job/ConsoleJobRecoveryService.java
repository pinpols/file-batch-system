package com.example.batch.console.application.job;

import com.example.batch.console.domain.governance.web.request.DeadLetterReplayRequest;
import com.example.batch.console.web.request.job.CompensateRequest;
import com.example.batch.console.web.request.job.CompensationCommandRequest;
import com.example.batch.console.web.request.job.PartitionReplayRequest;
import com.example.batch.console.web.request.job.RerunRequest;
import com.example.batch.console.web.request.job.TaskReplayRequest;

/** 控制台作业恢复服务：补偿、重跑、死信重放、分区重放、任务重放。 */
public interface ConsoleJobRecoveryService {

  /** 登记补偿类指令。 */
  String compensation(CompensationCommandRequest request, String idempotencyKey);

  /** 执行补偿动作。 */
  String compensate(CompensateRequest request, String idempotencyKey);

  /** 重跑指定作业实例或分区。 */
  String rerun(RerunRequest request, String idempotencyKey);

  /** 重放死信任务。 */
  String replayDeadLetter(DeadLetterReplayRequest request, String idempotencyKey);

  /** 重放指定任务（job_task 粒度）。 */
  String replayTask(TaskReplayRequest request, String idempotencyKey);

  /** 重放指定分区（job_partition 粒度）。 */
  String replayPartition(PartitionReplayRequest request, String idempotencyKey);
}
