package io.github.pinpols.batch.orchestrator.infrastructure.sla;

import io.github.pinpols.batch.common.logging.AuditLogConstants;
import io.github.pinpols.batch.common.logging.BatchMdc;
import io.github.pinpols.batch.common.logging.StructuredLogField;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.service.governance.AlertEventService;
import io.github.pinpols.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import io.github.pinpols.batch.orchestrator.controller.request.AlertEmitRequest;
import io.github.pinpols.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.JobExecutionLogMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 任务 SLA 违约扫描调度器。
 *
 * <p>默认每 30 秒扫描一次尚未标记告警、但已超过截止时间（{@code deadlineAt}）或 预期执行时长（{@code
 * expectedDurationSeconds}）的运行中任务实例，通过 CAS 更新 {@code sla_alerted_at} 字段防止重复告警，并写入 WARN 级告警日志与 INFO
 * 级审计日志， 同时向 {@link AlertEventService} 发送 {@code JOB_SLA_VIOLATION} 事件。 通过 Micrometer 指标 {@code
 * batch.job.sla.violation.count} 暴露当前违约候选总数。 ShedLock 锁名 {@code job_sla_scan}，最长持锁 2 分钟，最短持锁 15 秒；
 * 可通过 {@code batch.sla.enabled=false} 完全关闭扫描。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobSlaScheduler {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String KEY_VIOLATION_REASON = "violationReason";

  private final JobInstanceMapper jobInstanceMapper;
  private final JobExecutionLogMapper jobExecutionLogMapper;
  private final BatchOrchestratorGovernanceProperties governance;
  private final MeterRegistry meterRegistry;
  private final AlertEventService alertEventService;
  private final OrchestratorGracefulShutdown gracefulShutdown;
  private final AtomicLong violationCount = new AtomicLong();
  private final AtomicLong escalationCount = new AtomicLong();

  @PostConstruct
  void initializeMeters() {
    meterRegistry.gauge("batch.job.sla.violation.count", violationCount);
    meterRegistry.gauge("batch.job.sla.escalation.count", escalationCount);
  }

  @Scheduled(fixedDelayString = "${batch.sla.poll-interval-millis:30000}")
  @SchedulerLock(name = "job_sla_scan", lockAtMostFor = "PT2M", lockAtLeastFor = "PT15S")
  public void scanViolations() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    if (!governance.sla().isEnabled()) {
      return;
    }
    violationCount.set(jobInstanceMapper.countSlaViolationCandidates());
    List<JobInstanceEntity> candidates =
        jobInstanceMapper.selectSlaViolationCandidates(governance.sla().getBatchSize());
    Instant now = BatchDateTimeSupport.utcNow();
    for (JobInstanceEntity candidate : candidates) {
      if (candidate == null || candidate.getId() == null || candidate.getTenantId() == null) {
        continue;
      }
      if (jobInstanceMapper.markSlaAlerted(candidate.getTenantId(), candidate.getId(), now) <= 0) {
        continue;
      }
      BatchMdc.put(StructuredLogField.TENANT_ID, candidate.getTenantId());
      BatchMdc.put(StructuredLogField.TRACE_ID, candidate.getTraceId());
      BatchMdc.put(
          StructuredLogField.JOB_INSTANCE_ID,
          candidate.getId() == null ? null : String.valueOf(candidate.getId()));
      try {
        JobExecutionLogEntity logEntity = new JobExecutionLogEntity();
        logEntity.setTenantId(candidate.getTenantId());
        logEntity.setJobInstanceId(candidate.getId());
        logEntity.setLogLevel("WARN");
        logEntity.setLogType(AuditLogConstants.LOG_TYPE_ALARM);
        logEntity.setTraceId(candidate.getTraceId());
        logEntity.setMessage(buildMessage(candidate, now));
        logEntity.setDetailRef(AuditLogConstants.DETAIL_REF_JOB_SLA);
        logEntity.setExtraJson(JsonUtils.toJson(buildExtra(candidate, now)));
        jobExecutionLogMapper.insert(logEntity);

        // 同步落审计：sla_alerted_at 属于状态变更，需要追溯操作者与原因
        JobExecutionLogEntity auditEntity = new JobExecutionLogEntity();
        auditEntity.setTenantId(candidate.getTenantId());
        auditEntity.setJobInstanceId(candidate.getId());
        auditEntity.setJobPartitionId(null);
        auditEntity.setLogLevel("INFO");
        auditEntity.setLogType(AuditLogConstants.LOG_TYPE_AUDIT);
        auditEntity.setTraceId(candidate.getTraceId());
        auditEntity.setMessage("SLA_ALERTED_AT_UPDATED");
        auditEntity.setDetailRef(AuditLogConstants.DETAIL_REF_JOB_INSTANCE_SLA_ALERTED_AT);
        Map<String, Object> auditExtra = new LinkedHashMap<>(buildExtra(candidate, now));
        auditExtra.put("operatorId", AuditLogConstants.OPERATOR_ID_SYSTEM_SLA_SCHEDULER);
        auditExtra.put("operatorType", AuditLogConstants.OPERATOR_TYPE_SYSTEM);
        auditEntity.setExtraJson(JsonUtils.toJson(auditExtra));
        jobExecutionLogMapper.insert(auditEntity);
        log.warn(
            "job SLA violation detected: tenantId={}, jobInstanceId={}, instanceNo={},"
                + " extra={}",
            candidate.getTenantId(),
            candidate.getId(),
            candidate.getInstanceNo(),
            buildExtra(candidate, now));
        AlertEmitRequest emitRequest =
            AlertEmitRequest.builder()
                .tenantId(candidate.getTenantId())
                .serviceName("batch-orchestrator")
                .alertType("JOB_SLA_VIOLATION")
                .severity("WARN")
                .title(buildMessage(candidate, now))
                .detailJson(JsonUtils.toJson(buildExtra(candidate, now)))
                .resourceKey(String.valueOf(candidate.getId()))
                .traceId(candidate.getTraceId())
                .build();
        alertEventService.emit(emitRequest);
      } finally {
        BatchMdc.remove(StructuredLogField.JOB_INSTANCE_ID);
        BatchMdc.remove(StructuredLogField.TRACE_ID);
        BatchMdc.remove(StructuredLogField.TENANT_ID);
      }
    }
    scanEscalations(now);
  }

  /**
   * 升级再触发：首次告警后 instance 仍非终态、sla_alerted_at 早于 {@code now - escalationDelay} 的实例 → 以 ERROR 级再发一条
   * {@code JOB_SLA_VIOLATION_ESCALATED}。alert_event 表按 fingerprint 去重（{@code tenant + alertType +
   * resourceKey}），重复扫描会 merge 到同一行而非刷屏；本地不写 execution log，避免日志爆失败。
   */
  private void scanEscalations(Instant now) {
    long delaySeconds = governance.sla().getEscalationDelaySeconds();
    if (delaySeconds <= 0L) {
      escalationCount.set(0L);
      return;
    }
    Instant escalationBefore = now.minusSeconds(delaySeconds);
    escalationCount.set(jobInstanceMapper.countSlaEscalationCandidates(escalationBefore));
    List<JobInstanceEntity> escalations =
        jobInstanceMapper.selectSlaEscalationCandidates(
            escalationBefore, governance.sla().getBatchSize());
    if (escalations == null || escalations.isEmpty()) {
      return;
    }
    String severity = governance.sla().getEscalationSeverity();
    String resolvedSeverity = severity == null || severity.isBlank() ? "ERROR" : severity;
    for (JobInstanceEntity candidate : escalations) {
      if (candidate == null || candidate.getId() == null || candidate.getTenantId() == null) {
        continue;
      }
      Map<String, Object> extra = buildEscalationExtra(candidate, now, delaySeconds);
      AlertEmitRequest emitRequest =
          AlertEmitRequest.builder()
              .tenantId(candidate.getTenantId())
              .serviceName("batch-orchestrator")
              .alertType("JOB_SLA_VIOLATION_ESCALATED")
              .severity(resolvedSeverity)
              .title("job SLA still violating after escalation window")
              .detailJson(JsonUtils.toJson(extra))
              .resourceKey(String.valueOf(candidate.getId()))
              .traceId(candidate.getTraceId())
              .build();
      alertEventService.emit(emitRequest);
    }
  }

  private Map<String, Object> buildEscalationExtra(
      JobInstanceEntity candidate, Instant now, long delaySeconds) {
    Map<String, Object> extra = new LinkedHashMap<>(buildExtra(candidate, now));
    extra.put("escalationDelaySeconds", delaySeconds);
    extra.put("slaAlertedAt", candidate.getSlaAlertedAt());
    if (candidate.getSlaAlertedAt() != null) {
      extra.put(
          "alertedElapsedSeconds", Duration.between(candidate.getSlaAlertedAt(), now).getSeconds());
    }
    return extra;
  }

  private String buildMessage(JobInstanceEntity candidate, Instant now) {
    Map<String, Object> extra = buildExtra(candidate, now);
    return "job SLA violated: " + extra.get(KEY_VIOLATION_REASON);
  }

  private Map<String, Object> buildExtra(JobInstanceEntity candidate, Instant now) {
    Map<String, Object> extra = new LinkedHashMap<>();
    extra.put("instanceNo", candidate.getInstanceNo());
    extra.put("jobCode", candidate.getJobCode());
    extra.put("instanceStatus", candidate.getInstanceStatus());
    extra.put("deadlineAt", candidate.getDeadlineAt());
    extra.put("expectedDurationSeconds", candidate.getExpectedDurationSeconds());
    if (candidate.getDeadlineAt() != null && candidate.getDeadlineAt().isBefore(now)) {
      extra.put(KEY_VIOLATION_REASON, "DEADLINE_EXCEEDED");
      extra.put(
          "deadlineDelaySeconds", Duration.between(candidate.getDeadlineAt(), now).getSeconds());
    } else if (candidate.getExpectedDurationSeconds() != null
        && candidate.getExpectedDurationSeconds() > 0
        && candidate.getStartedAt() != null) {
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
