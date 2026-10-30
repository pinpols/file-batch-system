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
    JobInstanceEntity claimedCandidate =
        jobMonitoringMapper.selectSlaViolationCandidate(candidate.getTenantId(), candidate.getId());
    if (EmptyChecks.isNull(claimedCandidate)) {
      throw new IllegalStateException("claimed SLA violation candidate is no longer available");
    }
    candidate = claimedCandidate;
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
      String alertExtraJson = JsonUtils.toJson(extra);
      alertLog.setExtraJson(alertExtraJson);
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
      String auditExtraJson = JsonUtils.toJson(auditExtra);
      auditLog.setExtraJson(auditExtraJson);
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

  /** 候选扫描之外再锁定当前实例,让终态提交与升级通知有确定的先后顺序。 */
  @Transactional
  public boolean processEscalation(
      JobInstanceEntity observed, Instant now, long delaySeconds, String severity) {
    JobInstanceEntity candidate = jobMonitoringMapper.selectSlaEscalationCandidateForUpdate(
        observed.getTenantId(), observed.getId(), now.minusSeconds(delaySeconds));
    if (EmptyChecks.isNull(candidate)) {
      return false;
    }
    Map<String, Object> extra = buildEscalationExtra(candidate, now, delaySeconds);
    AlertEmitRequest.AlertEmitRequestBuilder requestBuilder = AlertEmitRequest.builder()
        .tenantId(candidate.getTenantId())
        .serviceName("batch-orchestrator")
        .alertType("JOB_SLA_VIOLATION_ESCALATED")
        .severity(severity)
        .title("job SLA still violating after escalation window");
    String detailJson = JsonUtils.toJson(extra);
    AlertEmitRequest request = requestBuilder
        .detailJson(detailJson)
        .resourceKey(String.valueOf(candidate.getId()))
        .traceId(candidate.getTraceId())
        .build();
    alertEventService.emit(request);
    return true;
  }

  Map<String, Object> buildEscalationExtra(
      JobInstanceEntity candidate, Instant now, long delaySeconds) {
    Map<String, Object> baseExtra = buildExtra(candidate, now);
    Map<String, Object> extra = new LinkedHashMap<>(baseExtra);
    extra.put("escalationDelaySeconds", delaySeconds);
    extra.put("slaAlertedAt", candidate.getSlaAlertedAt());
    if (EmptyChecks.isNotNull(candidate.getSlaAlertedAt())) {
      Duration elapsedSinceAlert = Duration.between(candidate.getSlaAlertedAt(), now);
      long alertedElapsedSeconds = elapsedSinceAlert.getSeconds();
      extra.put("alertedElapsedSeconds", alertedElapsedSeconds);
    }
    return extra;
  }

  private Map<String, Object> buildExtra(JobInstanceEntity candidate, Instant now) {
    Map<String, Object> extra = new LinkedHashMap<>();
    extra.put("jobInstanceId", String.valueOf(candidate.getId()));
    extra.put(
        "bizDate",
        EmptyChecks.isNull(candidate.getBizDate())
            ? null
            : candidate.getBizDate().toString());
    if (EmptyChecks.isNotNull(candidate.getParentWorkflowRunId())) {
      extra.put("workflowRunId", candidate.getParentWorkflowRunId().toString());
    }
    if (!EmptyChecks.isBlank(candidate.getParentNodeCode())) {
      extra.put("nodeCode", candidate.getParentNodeCode());
    }
    extra.put("instanceNo", candidate.getInstanceNo());
    extra.put("jobCode", candidate.getJobCode());
    extra.put("instanceStatus", candidate.getInstanceStatus());
    extra.put("deadlineAt", candidate.getDeadlineAt());
    extra.put("expectedDurationSeconds", candidate.getExpectedDurationSeconds());
    extra.put("dispatchedAt", candidate.getStartedAt());
    extra.put("executionStartedAt", candidate.getExecutionStartedAt());
    Instant finishedAt = candidate.getFinishedAt();
    Instant evaluatedAt = EmptyChecks.isNotNull(finishedAt) ? finishedAt : now;
    extra.put("finishedAt", finishedAt);
    extra.put("violationDetectedAt", now);
    extra.put("violationEvaluatedAt", evaluatedAt);
    if (EmptyChecks.isNotNull(candidate.getDeadlineAt())
        && candidate.getDeadlineAt().isBefore(evaluatedAt)) {
      extra.put(KEY_VIOLATION_REASON, "DEADLINE_EXCEEDED");
      Duration deadlineOverrun = Duration.between(candidate.getDeadlineAt(), evaluatedAt);
      long deadlineDelaySeconds = deadlineOverrun.getSeconds();
      extra.put("deadlineDelaySeconds", deadlineDelaySeconds);
    } else if (EmptyChecks.isNotNull(candidate.getExpectedDurationSeconds())
        && candidate.getExpectedDurationSeconds() > 0
        && EmptyChecks.isNotNull(candidate.getExecutionStartedAt())) {
      Instant startedAt = candidate.getExecutionStartedAt();
      long expectedDurationSeconds = candidate.getExpectedDurationSeconds();
      Instant expectedFinish = startedAt.plusSeconds(expectedDurationSeconds);
      extra.put(KEY_VIOLATION_REASON, "EXPECTED_DURATION_EXCEEDED");
      extra.put("expectedFinishAt", expectedFinish);
      Duration durationOverrun = Duration.between(expectedFinish, evaluatedAt);
      long durationDelaySeconds = durationOverrun.getSeconds();
      extra.put("durationDelaySeconds", durationDelaySeconds);
    } else {
      extra.put(KEY_VIOLATION_REASON, "UNKNOWN");
    }
    return extra;
  }
}
