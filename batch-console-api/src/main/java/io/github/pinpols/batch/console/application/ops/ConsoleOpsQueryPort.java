package io.github.pinpols.batch.console.application.ops;

import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.console.domain.audit.web.query.ConsoleAiAuditLogQueryRequest;
import io.github.pinpols.batch.console.domain.audit.web.response.AiAuditLogResponse;
import io.github.pinpols.batch.console.domain.governance.web.query.DeadLetterQueryRequest;
import io.github.pinpols.batch.console.domain.governance.web.response.ConsoleDeadLetterTaskResponse;
import io.github.pinpols.batch.console.domain.job.web.query.BatchDayQueryRequest;
import io.github.pinpols.batch.console.domain.job.web.query.BatchDayWindowQueryRequest;
import io.github.pinpols.batch.console.domain.job.web.query.PendingCatchUpQueryRequest;
import io.github.pinpols.batch.console.domain.job.web.response.ConsoleBatchDayResponse;
import io.github.pinpols.batch.console.domain.job.web.response.ConsoleBatchDayWindowResponse;
import io.github.pinpols.batch.console.domain.job.web.response.ConsoleRetryScheduleResponse;
import io.github.pinpols.batch.console.domain.notification.web.query.AlertEventQueryRequest;
import io.github.pinpols.batch.console.domain.notification.web.response.ConsoleAlertEventResponse;
import io.github.pinpols.batch.console.shared.view.ConsoleApprovalCommandResponse;
import io.github.pinpols.batch.console.shared.view.ConsoleAuditLogResponse;
import io.github.pinpols.batch.console.shared.view.ConsoleOutboxDeliveryLogResponse;
import io.github.pinpols.batch.console.shared.view.ConsoleOutboxRetryLogResponse;
import io.github.pinpols.batch.console.shared.view.ConsolePendingCatchUpResponse;
import io.github.pinpols.batch.console.shared.view.ConsoleWorkerRegistryResponse;
import io.github.pinpols.batch.console.web.query.ApprovalCommandQueryRequest;
import io.github.pinpols.batch.console.web.query.AuditLogQueryRequest;
import io.github.pinpols.batch.console.web.query.OutboxDeliveryLogQueryRequest;
import io.github.pinpols.batch.console.web.query.OutboxRetryLogQueryRequest;
import io.github.pinpols.batch.console.web.query.RetryScheduleQueryRequest;
import io.github.pinpols.batch.console.web.query.WorkerRegistryQueryRequest;

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
