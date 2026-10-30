package io.github.pinpols.batch.console.domain.job.application;

import io.github.pinpols.batch.console.domain.job.application.contract.response.BatchDayCatchUpRequestStatusResponse;
import java.time.LocalDate;

/** 按原请求核对审批回执或接续待处理项目。 */
public interface BatchDayCatchUpRecoveryService {
  BatchDayCatchUpRequestStatusResponse abandonUnprepared(
      String tenantId, String requestKey, LocalDate bizDate, String calendarCode);

  BatchDayCatchUpRequestStatusResponse continueRequest(String tenantId, String requestKey);

  BatchDayCatchUpRequestStatusResponse reconcile(
      String tenantId, String requestKey, String jobCode);
}
