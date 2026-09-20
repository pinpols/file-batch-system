package io.github.pinpols.batch.console.application.ops;

import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.console.application.contract.query.ApprovalCommandQueryRequest;
import io.github.pinpols.batch.console.application.contract.query.AuditLogQueryRequest;
import io.github.pinpols.batch.console.application.contract.query.OutboxDeliveryLogQueryRequest;
import io.github.pinpols.batch.console.application.contract.query.OutboxRetryLogQueryRequest;
import io.github.pinpols.batch.console.application.contract.query.RetryScheduleQueryRequest;
import io.github.pinpols.batch.console.application.contract.query.WorkerRegistryQueryRequest;
import io.github.pinpols.batch.console.domain.audit.application.contract.query.ConsoleAiAuditLogQueryRequest;
import io.github.pinpols.batch.console.domain.audit.application.contract.response.AiAuditLogResponse;
import io.github.pinpols.batch.console.domain.governance.application.contract.query.DeadLetterQueryRequest;
import io.github.pinpols.batch.console.domain.governance.application.contract.response.ConsoleDeadLetterTaskResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.query.BatchDayQueryRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.query.BatchDayWindowQueryRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.query.PendingCatchUpQueryRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleBatchDayResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleBatchDayWindowResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleRetryScheduleResponse;
import io.github.pinpols.batch.console.domain.notification.application.contract.query.AlertEventQueryRequest;
import io.github.pinpols.batch.console.domain.notification.application.contract.response.ConsoleAlertEventResponse;
import io.github.pinpols.batch.console.shared.view.ConsoleApprovalCommandResponse;
import io.github.pinpols.batch.console.shared.view.ConsoleAuditLogResponse;
import io.github.pinpols.batch.console.shared.view.ConsoleOutboxDeliveryLogResponse;
import io.github.pinpols.batch.console.shared.view.ConsoleOutboxRetryLogResponse;
import io.github.pinpols.batch.console.shared.view.ConsolePendingCatchUpResponse;
import io.github.pinpols.batch.console.shared.view.ConsoleWorkerRegistryResponse;

/** Ops 只读查询端口，供跨领域聚合服务依赖。 */
public interface ConsoleOpsQueryPort {

  PageResponse<ConsoleAuditLogResponse> auditLogs(AuditLogQueryRequest request);

  PageResponse<ConsoleAuditLogResponse> executionLogs(AuditLogQueryRequest request);

  PageResponse<ConsoleOutboxRetryLogResponse> outboxRetries(OutboxRetryLogQueryRequest request);

  PageResponse<ConsoleOutboxDeliveryLogResponse> outboxDeliveries(
      OutboxDeliveryLogQueryRequest request);

  PageResponse<AiAuditLogResponse> aiAuditLogs(ConsoleAiAuditLogQueryRequest request);

  PageResponse<ConsoleDeadLetterTaskResponse> deadLetters(DeadLetterQueryRequest request);

  PageResponse<ConsoleRetryScheduleResponse> retries(RetryScheduleQueryRequest request);

  PageResponse<ConsolePendingCatchUpResponse> pendingCatchUps(PendingCatchUpQueryRequest request);

  PageResponse<ConsoleWorkerRegistryResponse> workers(WorkerRegistryQueryRequest request);

  PageResponse<ConsoleAlertEventResponse> alertEvents(AlertEventQueryRequest request);

  PageResponse<ConsoleBatchDayResponse> batchDays(BatchDayQueryRequest request);

  ConsoleBatchDayWindowResponse batchDayWindow(String bizDate, BatchDayWindowQueryRequest request);

  PageResponse<ConsoleApprovalCommandResponse> approvals(ApprovalCommandQueryRequest request);
}
