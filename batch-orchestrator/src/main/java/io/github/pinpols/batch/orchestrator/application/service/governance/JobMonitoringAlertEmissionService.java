package io.github.pinpols.batch.orchestrator.application.service.governance;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.controller.request.AlertEmitRequest;
import io.github.pinpols.batch.orchestrator.domain.entity.JobMonitoringAlertCandidate;
import io.github.pinpols.batch.orchestrator.mapper.JobMonitoringMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 在独立事务中先声明告警幂等键，再持久化告警事件；失败时两者一起回滚供下一轮重试。 */
@Service
@RequiredArgsConstructor
public class JobMonitoringAlertEmissionService {

  private final JobMonitoringMapper jobMonitoringMapper;
  private final AlertEventService alertEventService;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean emitOnce(JobMonitoringAlertCandidate candidate, Violation violation) {
    int claimed = jobMonitoringMapper.claimAlert(
        candidate.tenantId(), candidate.jobInstanceId(), violation.name());
    if (claimed == 0) {
      return false;
    }

    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("jobCode", candidate.jobCode());
    detail.put("instanceNo", candidate.instanceNo());
    detail.put("instanceStatus", candidate.instanceStatus());
    detail.put("triggerType", candidate.triggerType());
    detail.put("monitoringDeadlineAt", candidate.monitoringDeadlineAt());
    detail.put("thresholdSeconds", candidate.thresholdSeconds());
    detail.put("createdAt", candidate.createdAt());
    detail.put("startedAt", candidate.startedAt());
    detail.put("finishedAt", candidate.finishedAt());
    if (violation == Violation.FAILED_PARTITION) {
      detail.put("failedPartitionCount", candidate.failedPartitionCount());
    }

    AlertEmitRequest request = AlertEmitRequest.builder()
        .tenantId(candidate.tenantId())
        .serviceName("batch-orchestrator")
        .alertType(violation.alertType())
        .severity(EmptyChecks.isBlank(candidate.severity()) ? "WARN" : candidate.severity())
        .title(violation.title(candidate.jobCode()))
        .detailJson(JsonUtils.toJson(detail))
        .resourceKey(String.valueOf(candidate.jobInstanceId()))
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
