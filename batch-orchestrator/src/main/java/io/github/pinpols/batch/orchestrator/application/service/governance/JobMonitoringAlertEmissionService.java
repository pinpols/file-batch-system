package io.github.pinpols.batch.orchestrator.application.service.governance;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.config.SlaGovernanceProperties;
import io.github.pinpols.batch.orchestrator.controller.request.AlertEmitRequest;
import io.github.pinpols.batch.orchestrator.domain.query.JobMonitoringRecheckQuery;
import io.github.pinpols.batch.orchestrator.domain.view.JobMonitoringAlertCandidate;
import io.github.pinpols.batch.orchestrator.mapper.JobMonitoringMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 持有实例锁重新核对违约后，在独立事务中声明幂等键并持久化告警；失败时一起回滚。 */
@Service
@RequiredArgsConstructor
public class JobMonitoringAlertEmissionService {

  private final JobMonitoringMapper jobMonitoringMapper;
  private final AlertEventService alertEventService;
  private final SlaGovernanceProperties properties;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean emitOnce(JobMonitoringAlertCandidate scanned, Violation violation) {
    String tenantId = scanned.tenantId();
    Long jobInstanceId = scanned.jobInstanceId();
    Long triggerRequestId = scanned.triggerRequestId();
    jobMonitoringMapper.setTenantContext(tenantId);
    boolean lockAcquired = EmptyChecks.isNotNull(jobInstanceId)
        ? jobMonitoringMapper.tryAcquireInstanceAdvisoryLock(tenantId, jobInstanceId)
        : EmptyChecks.isNotNull(triggerRequestId)
            && jobMonitoringMapper.tryAcquireRequestAdvisoryLock(tenantId, triggerRequestId);
    if (!lockAcquired) {
      return false;
    }
    int lookbackSeconds = properties.getJobMonitoring().getFailedPartitionLookbackSeconds();
    JobMonitoringRecheckQuery query = new JobMonitoringRecheckQuery(
        tenantId, jobInstanceId, triggerRequestId, violation.name(), lookbackSeconds);
    JobMonitoringAlertCandidate candidate = jobMonitoringMapper.selectCandidateForRecheck(query);
    if (EmptyChecks.isNull(candidate)) {
      return false;
    }
    if (EmptyChecks.isNotNull(candidate.jobInstanceId())) {
      int claimed = jobMonitoringMapper.claimAlert(
          candidate.tenantId(), candidate.jobInstanceId(), violation.name());
      if (claimed == 0) {
        return false;
      }
    }

    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put(
        "jobInstanceId",
        EmptyChecks.isNull(candidate.jobInstanceId())
            ? null
            : candidate.jobInstanceId().toString());
    detail.put(
        "bizDate",
        EmptyChecks.isNull(candidate.bizDate()) ? null : candidate.bizDate().toString());
    if (EmptyChecks.isNotNull(candidate.workflowRunId())) {
      detail.put("workflowRunId", candidate.workflowRunId().toString());
    }
    if (EmptyChecks.isNotNull(candidate.triggerRequestId())) {
      detail.put("triggerRequestId", candidate.triggerRequestId().toString());
    }
    if (!EmptyChecks.isBlank(candidate.nodeCode())) {
      detail.put("nodeCode", candidate.nodeCode());
    }
    detail.put("jobCode", candidate.jobCode());
    detail.put("instanceNo", candidate.instanceNo());
    detail.put("instanceStatus", candidate.instanceStatus());
    detail.put("triggerType", candidate.triggerType());
    detail.put("monitoringDeadlineAt", candidate.monitoringDeadlineAt());
    detail.put("thresholdSeconds", candidate.thresholdSeconds());
    detail.put("createdAt", candidate.createdAt());
    detail.put("startedAt", candidate.startedAt());
    detail.put("executionStartedAt", candidate.executionStartedAt());
    detail.put(
        "executionTimingSource",
        EmptyChecks.isNull(candidate.executionStartedAt())
            ? "UNOBSERVED"
            : "WORKER_ENTRY_CONFIRMATION");
    detail.put("finishedAt", candidate.finishedAt());
    if (violation == Violation.FAILED_PARTITION) {
      detail.put("failedPartitionCount", candidate.failedPartitionCount());
    }
    boolean isWorkflowChildJob = EmptyChecks.isNotNull(candidate.workflowRunId())
        && EmptyChecks.isNotNull(candidate.triggerRequestId());
    String monitoringResourceKey =
        isWorkflowChildJob ? "workflow_job_request:" + candidate.triggerRequestId() : null;
    if (EmptyChecks.isNotNull(monitoringResourceKey)) {
      detail.put("monitoringResourceKey", monitoringResourceKey);
    }

    String detailJson = JsonUtils.toJson(detail);
    AlertEmitRequest request = AlertEmitRequest.builder()
        .tenantId(candidate.tenantId())
        .serviceName("batch-orchestrator")
        .alertType(violation.alertType())
        .severity(EmptyChecks.isBlank(candidate.severity()) ? "WARN" : candidate.severity())
        .title(violation.title(candidate.jobCode()))
        .detailJson(detailJson)
        .resourceKey(
            EmptyChecks.isNull(monitoringResourceKey)
                ? String.valueOf(candidate.jobInstanceId())
                : monitoringResourceKey)
        .traceId(candidate.traceId())
        .build();
    alertEventService.emit(request);
    return true;
  }

  public enum Violation {
    RUNNING_TOO_LONG("JOB_RUNNING_TOO_LONG", "作业运行耗时超过配置阈值"),
    NOT_STARTED("JOB_NOT_STARTED_BY_DEADLINE", "作业启动过晚"),
    NOT_COMPLETED_BY_DEADLINE("JOB_NOT_COMPLETED_BY_DEADLINE", "作业完成过晚"),
    FAILED_PARTITION("JOB_FINAL_PARTITION_FAILURE", "作业终态仍存在失败分区");

    private final String alertType;
    private final String title;

    Violation(String alertType, String title) {
      this.alertType = alertType;
      this.title = title;
    }

    public String alertType() {
      return alertType;
    }

    public String title(String jobCode) {
      return title + "：" + jobCode;
    }
  }
}
