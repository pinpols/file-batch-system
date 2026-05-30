package com.example.batch.console.domain.job.application;

import com.example.batch.console.domain.job.web.request.BatchDayCatchUpRequest;
import com.example.batch.console.domain.job.web.response.ConsoleBatchDayCatchUpResponse;
import com.example.batch.console.domain.ops.web.request.ConsoleCatchUpApprovalRequest;

/** 控制台作业审批服务：Catch-Up 审批、批量日 Catch-Up。 */
public interface ConsoleJobApprovalService {

  /** 审批通过待处理的 Catch-Up 请求。 */
  String approveCatchUp(ConsoleCatchUpApprovalRequest request, String idempotencyKey);

  /** 按批量日发起 catch-up。 */
  ConsoleBatchDayCatchUpResponse catchUpBatchDay(
      String bizDate, BatchDayCatchUpRequest request, String idempotencyKey);
}
