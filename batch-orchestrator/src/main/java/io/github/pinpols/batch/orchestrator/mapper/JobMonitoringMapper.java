package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.view.JobMonitoringAlertCandidate;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 作业监控查询与告警标记；不推进 job_instance 生命周期状态。 */
public interface JobMonitoringMapper {

  List<JobInstanceEntity> selectSlaViolationCandidates(@Param("limit") int limit);

  long countSlaViolationCandidates();

  List<JobInstanceEntity> selectSlaEscalationCandidates(
      @Param("escalationBefore") Instant escalationBefore, @Param("limit") int limit);

  long countSlaEscalationCandidates(@Param("escalationBefore") Instant escalationBefore);

  int markSlaAlerted(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("slaAlertedAt") Instant slaAlertedAt);

  List<JobMonitoringAlertCandidate> selectRunningTooLong(@Param("limit") int limit);

  List<JobMonitoringAlertCandidate> selectNotStarted(@Param("limit") int limit);

  List<JobMonitoringAlertCandidate> selectNotCompletedByDeadline(@Param("limit") int limit);

  List<JobMonitoringAlertCandidate> selectFinalPartitionFailures(
      @Param("limit") int limit, @Param("lookbackSeconds") int lookbackSeconds);

  int claimAlert(
      @Param("tenantId") String tenantId,
      @Param("jobInstanceId") Long jobInstanceId,
      @Param("violationType") String violationType);
}
