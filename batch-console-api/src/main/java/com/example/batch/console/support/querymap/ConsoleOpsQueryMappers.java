package com.example.batch.console.support.querymap;

import com.example.batch.console.domain.audit.mapper.ConsoleAiAuditLogMapper;
import com.example.batch.console.domain.governance.mapper.DeadLetterTaskMapper;
import com.example.batch.console.domain.notification.mapper.AlertEventMapper;
import com.example.batch.console.domain.job.mapper.BatchDayMapper;
import com.example.batch.console.domain.job.mapper.BusinessCalendarMapper;
import com.example.batch.console.domain.job.mapper.PendingCatchUpMapper;
import com.example.batch.console.mapper.ApprovalCommandMapper;
import com.example.batch.console.mapper.AuditLogMapper;
import com.example.batch.console.mapper.OutboxDeliveryLogMapper;
import com.example.batch.console.mapper.OutboxRetryLogMapper;
import com.example.batch.console.mapper.RetryScheduleMapper;
import com.example.batch.console.mapper.WorkerRegistryMapper;
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
