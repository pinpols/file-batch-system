package com.example.batch.orchestrator.infrastructure.sla;

import com.example.batch.common.logging.AuditLogConstants;
import com.example.batch.common.logging.BatchMdc;
import com.example.batch.common.logging.StructuredLogField;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.application.service.AlertEventService;
import com.example.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;
import com.example.batch.orchestrator.controller.request.AlertEmitRequest;
import com.example.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.mapper.JobExecutionLogMapper;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class JobSlaScheduler {

  private final JobInstanceMapper jobInstanceMapper;
  private final JobExecutionLogMapper jobExecutionLogMapper;
  private final BatchOrchestratorGovernanceProperties governance;
  private final MeterRegistry meterRegistry;
  private final AlertEventService alertEventService;
  private final OrchestratorGracefulShutdown gracefulShutdown;
  private final AtomicLong violationCount = new AtomicLong();

  @PostConstruct
  void initializeMeters() {
    meterRegistry.gauge("batch.job.sla.violation.count", violationCount);
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
    Instant now = Instant.now();
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
        alertEventService.emit(
            new AlertEmitRequest(
                candidate.getTenantId(),
                "batch-orchestrator",
                "JOB_SLA_VIOLATION",
                "WARN",
                buildMessage(candidate, now),
                JsonUtils.toJson(buildExtra(candidate, now)),
                String.valueOf(candidate.getId()),
                candidate.getTraceId()));
      } finally {
        BatchMdc.remove(StructuredLogField.JOB_INSTANCE_ID);
        BatchMdc.remove(StructuredLogField.TRACE_ID);
        BatchMdc.remove(StructuredLogField.TENANT_ID);
      }
    }
  }

  private String buildMessage(JobInstanceEntity candidate, Instant now) {
    Map<String, Object> extra = buildExtra(candidate, now);
    return "job SLA violated: " + extra.get("violationReason");
  }

  private Map<String, Object> buildExtra(JobInstanceEntity candidate, Instant now) {
    Map<String, Object> extra = new LinkedHashMap<>();
    extra.put("instanceNo", candidate.getInstanceNo());
    extra.put("jobCode", candidate.getJobCode());
    extra.put("instanceStatus", candidate.getInstanceStatus());
    extra.put("deadlineAt", candidate.getDeadlineAt());
    extra.put("expectedDurationSeconds", candidate.getExpectedDurationSeconds());
    if (candidate.getDeadlineAt() != null && candidate.getDeadlineAt().isBefore(now)) {
      extra.put("violationReason", "DEADLINE_EXCEEDED");
      extra.put(
          "deadlineDelaySeconds", Duration.between(candidate.getDeadlineAt(), now).getSeconds());
    } else if (candidate.getExpectedDurationSeconds() != null
        && candidate.getExpectedDurationSeconds() > 0
        && candidate.getStartedAt() != null) {
      Instant expectedFinish =
          candidate.getStartedAt().plusSeconds(candidate.getExpectedDurationSeconds());
      extra.put("violationReason", "EXPECTED_DURATION_EXCEEDED");
      extra.put("expectedFinishAt", expectedFinish);
      extra.put("durationDelaySeconds", Duration.between(expectedFinish, now).getSeconds());
    } else {
      extra.put("violationReason", "UNKNOWN");
    }
    return extra;
  }
}
