package io.github.pinpols.batch.console.domain.ops.support;

import io.github.pinpols.batch.console.domain.audit.mapper.ConsoleAiAuditLogMapper;
import io.github.pinpols.batch.console.domain.governance.mapper.DeadLetterTaskMapper;
import io.github.pinpols.batch.console.domain.job.mapper.BatchDayMapper;
import io.github.pinpols.batch.console.domain.job.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.console.domain.job.mapper.PendingCatchUpMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.AlertEventMapper;
import io.github.pinpols.batch.console.domain.ops.mapper.ApprovalCommandMapper;
import io.github.pinpols.batch.console.domain.ops.mapper.OutboxDeliveryLogMapper;
import io.github.pinpols.batch.console.domain.ops.mapper.OutboxRetryLogMapper;
import io.github.pinpols.batch.console.domain.ops.mapper.RetryScheduleMapper;
import io.github.pinpols.batch.console.domain.ops.mapper.WorkerRegistryMapper;
import io.github.pinpols.batch.console.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ConsoleOpsQueryMappers {

  public final AuditLogMapper auditLogMapper;
  public final AlertEventMapper alertEventMapper;
  public final ApprovalCommandMapper approvalCommandMapper;
  public final DeadLetterTaskMapper deadLetterTaskMapper;
  public final RetryScheduleMapper retryScheduleMapper;
  public final PendingCatchUpMapper pendingCatchUpMapper;
  public final WorkerRegistryMapper workerRegistryMapper;
  public final ConsoleAiAuditLogMapper consoleAiAuditLogMapper;
  public final OutboxRetryLogMapper outboxRetryLogMapper;
  public final OutboxDeliveryLogMapper outboxDeliveryLogMapper;
  public final BatchDayMapper batchDayMapper;
  public final BusinessCalendarMapper businessCalendarMapper;
}
