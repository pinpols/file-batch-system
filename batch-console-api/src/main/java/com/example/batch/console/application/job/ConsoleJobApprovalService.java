package com.example.batch.console.application.job;

import com.example.batch.console.web.request.ops.BatchDayCatchUpRequest;
import com.example.batch.console.domain.ops.web.request.ConsoleCatchUpApprovalRequest;
import com.example.batch.console.web.response.file.ConsoleBatchDayCatchUpResponse;

/** 控制台作业审批服务：Catch-Up 审批、批量日 Catch-Up。 */
public interface ConsoleJobApprovalService {

  /** 审批通过待处理的 Catch-Up 请求。 */
  String approveCatchUp(ConsoleCatchUpApprovalRequest request, String idempotencyKey);

  /** 按批量日发起 catch-up。 */
  ConsoleBatchDayCatchUpResponse catchUpBatchDay(
      String bizDate, BatchDayCatchUpRequest request, String idempotencyKey);
}
