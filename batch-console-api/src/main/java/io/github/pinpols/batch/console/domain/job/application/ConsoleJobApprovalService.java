package io.github.pinpols.batch.console.domain.job.application;

import io.github.pinpols.batch.console.domain.job.application.contract.request.BatchDayCatchUpRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.response.BatchDayCatchUpRequestStatusResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleBatchDayCatchUpResponse;
import io.github.pinpols.batch.console.shared.command.ConsoleCatchUpApprovalRequest;

/** 控制台作业审批服务：Catch-Up 审批、批量日 Catch-Up。 */
public interface ConsoleJobApprovalService {

  /** 审批通过待处理的 Catch-Up 请求。 */
  String approveCatchUp(ConsoleCatchUpApprovalRequest request, String idempotencyKey);

  /** 按批量日发起 catch-up。 */
  ConsoleBatchDayCatchUpResponse catchUpBatchDay(
      String bizDate, BatchDayCatchUpRequest request, String idempotencyKey);

  /** 按冻结快照接续尚未发出的项目；未确定项目不能重发。 */
  ConsoleBatchDayCatchUpResponse continueBatchDay(String tenantId, String requestKey);

  /** 查询原批次记录；未找到不代表可以使用新键重发。 */
  BatchDayCatchUpRequestStatusResponse batchDayCatchUpStatus(String tenantId, String requestKey);
}
