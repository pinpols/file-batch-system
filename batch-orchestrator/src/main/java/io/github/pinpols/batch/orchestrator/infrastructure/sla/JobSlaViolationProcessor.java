package io.github.pinpols.batch.orchestrator.infrastructure.sla;

import io.github.pinpols.batch.common.logging.AuditLogConstants;
import io.github.pinpols.batch.common.logging.BatchMdc;
import io.github.pinpols.batch.common.logging.StructuredLogField;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.service.governance.AlertEventService;
import io.github.pinpols.batch.orchestrator.controller.request.AlertEmitRequest;
import io.github.pinpols.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.mapper.JobExecutionLogMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobMonitoringMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 原子处理单个 SLA 违约候选，失败时只回滚当前候选以便后续扫描重试。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobSlaViolationProcessor {

  private static final String KEY_VIOLATION_REASON = "violationReason";

  private final JobMonitoringMapper jobMonitoringMapper;
  private final JobExecutionLogMapper jobExecutionLogMapper;
  private final AlertEventService alertEventService;

  @Transactional
  public boolean process(JobInstanceEntity candidate, Instant now) {
    if (jobMonitoringMapper.markSlaAlerted(candidate.getTenantId(), candidate.getId(), now) <= 0) {
      return false;
    }
    BatchMdc.put(StructuredLogField.TENANT_ID, candidate.getTenantId());
    BatchMdc.put(StructuredLogField.TRACE_ID, candidate.getTraceId());
    BatchMdc.put(StructuredLogField.JOB_INSTANCE_ID, String.valueOf(candidate.getId()));
    try {
      Map<String, Object> extra = buildExtra(candidate, now);
      JobExecutionLogEntity alertLog = new JobExecutionLogEntity();
      alertLog.setTenantId(candidate.getTenantId());
      alertLog.setJobInstanceId(candidate.getId());
      alertLog.setLogLevel("WARN");
      alertLog.setLogType(AuditLogConstants.LOG_TYPE_ALARM);
      alertLog.setTraceId(candidate.getTraceId());
      alertLog.setMessage("job SLA violated: " + extra.get(KEY_VIOLATION_REASON));
      alertLog.setDetailRef(AuditLogConstants.DETAIL_REF_JOB_SLA);
      alertLog.setExtraJson(JsonUtils.toJson(extra));
      jobExecutionLogMapper.insert(alertLog);

      JobExecutionLogEntity auditLog = new JobExecutionLogEntity();
      auditLog.setTenantId(candidate.getTenantId());
      auditLog.setJobInstanceId(candidate.getId());
      auditLog.setLogLevel("INFO");
      auditLog.setLogType(AuditLogConstants.LOG_TYPE_AUDIT);
      auditLog.setTraceId(candidate.getTraceId());
      auditLog.setMessage("SLA_ALERTED_AT_UPDATED");
      auditLog.setDetailRef(AuditLogConstants.DETAIL_REF_JOB_INSTANCE_SLA_ALERTED_AT);
      Map<String, Object> auditExtra = new LinkedHashMap<>(extra);
      auditExtra.put("operatorId", AuditLogConstants.OPERATOR_ID_SYSTEM_SLA_SCHEDULER);
      auditExtra.put("operatorType", AuditLogConstants.OPERATOR_TYPE_SYSTEM);
      auditLog.setExtraJson(JsonUtils.toJson(auditExtra));
      jobExecutionLogMapper.insert(auditLog);

      AlertEmitRequest request = AlertEmitRequest.builder()
          .tenantId(candidate.getTenantId())
          .serviceName("batch-orchestrator")
          .alertType("JOB_SLA_VIOLATION")
          .severity("WARN")
          .title("job SLA violated: " + extra.get(KEY_VIOLATION_REASON))
          .detailJson(JsonUtils.toJson(extra))
          .resourceKey(String.valueOf(candidate.getId()))
          .traceId(candidate.getTraceId())
          .build();
      alertEventService.emit(request);
      log.warn(
          "job SLA violation detected: tenantId={}, jobInstanceId={}, instanceNo={}, extra={}",
          candidate.getTenantId(),
          candidate.getId(),
          candidate.getInstanceNo(),
          extra);
      return true;
    } finally {
      BatchMdc.remove(StructuredLogField.JOB_INSTANCE_ID);
      BatchMdc.remove(StructuredLogField.TRACE_ID);
      BatchMdc.remove(StructuredLogField.TENANT_ID);
    }
  }

  Map<String, Object> buildEscalationExtra(
      JobInstanceEntity candidate, Instant now, long delaySeconds) {
    Map<String, Object> extra = new LinkedHashMap<>(buildExtra(candidate, now));
    extra.put("escalationDelaySeconds", delaySeconds);
    extra.put("slaAlertedAt", candidate.getSlaAlertedAt());
    if (EmptyChecks.isNotNull(candidate.getSlaAlertedAt())) {
      extra.put(
          "alertedElapsedSeconds",
          Duration.between(candidate.getSlaAlertedAt(), now).getSeconds());
    }
    return extra;
  }

  private Map<String, Object> buildExtra(JobInstanceEntity candidate, Instant now) {
    Map<String, Object> extra = new LinkedHashMap<>();
    extra.put("instanceNo", candidate.getInstanceNo());
    extra.put("jobCode", candidate.getJobCode());
    extra.put("instanceStatus", candidate.getInstanceStatus());
    extra.put("deadlineAt", candidate.getDeadlineAt());
    extra.put("expectedDurationSeconds", candidate.getExpectedDurationSeconds());
    if (EmptyChecks.isNotNull(candidate.getDeadlineAt())
        && candidate.getDeadlineAt().isBefore(now)) {
      extra.put(KEY_VIOLATION_REASON, "DEADLINE_EXCEEDED");
      extra.put(
          "deadlineDelaySeconds",
          Duration.between(candidate.getDeadlineAt(), now).getSeconds());
    } else if (EmptyChecks.isNotNull(candidate.getExpectedDurationSeconds())
        && candidate.getExpectedDurationSeconds() > 0
        && EmptyChecks.isNotNull(candidate.getStartedAt())) {
      Instant expectedFinish =
          candidate.getStartedAt().plusSeconds(candidate.getExpectedDurationSeconds());
      extra.put(KEY_VIOLATION_REASON, "EXPECTED_DURATION_EXCEEDED");
      extra.put("expectedFinishAt", expectedFinish);
      extra.put("durationDelaySeconds", Duration.between(expectedFinish, now).getSeconds());
    } else {
      extra.put(KEY_VIOLATION_REASON, "UNKNOWN");
    }
    return extra;
  }
}
