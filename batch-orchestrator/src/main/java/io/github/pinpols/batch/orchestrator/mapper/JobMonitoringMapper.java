package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.query.JobMonitoringRecheckQuery;
import io.github.pinpols.batch.orchestrator.domain.view.JobMonitoringAlertCandidate;
import io.github.pinpols.batch.orchestrator.domain.view.JobSlaCandidate;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 作业监控查询与告警标记；不推进 job_instance 生命周期状态。 */
public interface JobMonitoringMapper {

  List<JobSlaCandidate> selectSlaViolationCandidates(@Param("limit") int limit);

  long countSlaViolationCandidates();

  List<JobSlaCandidate> selectSlaEscalationCandidates(
      @Param("escalationBefore") Instant escalationBefore, @Param("limit") int limit);

  long countSlaEscalationCandidates(@Param("escalationBefore") Instant escalationBefore);

  JobSlaCandidate selectSlaEscalationCandidateForUpdate(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("escalationBefore") Instant escalationBefore);

  int markSlaAlerted(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("slaAlertedAt") Instant slaAlertedAt);

  JobSlaCandidate selectSlaViolationCandidate(
      @Param("tenantId") String tenantId, @Param("id") Long id);

  List<JobMonitoringAlertCandidate> selectRunningTooLong(@Param("limit") int limit);

  List<JobMonitoringAlertCandidate> selectNotStarted(@Param("limit") int limit);

  List<JobMonitoringAlertCandidate> selectNotCompletedByDeadline(@Param("limit") int limit);

  List<JobMonitoringAlertCandidate> selectFinalPartitionFailures(
      @Param("limit") int limit, @Param("lookbackSeconds") int lookbackSeconds);

  boolean tryAcquireInstanceAdvisoryLock(
      @Param("tenantId") String tenantId, @Param("jobInstanceId") Long jobInstanceId);

  boolean tryAcquireRequestAdvisoryLock(
      @Param("tenantId") String tenantId, @Param("triggerRequestId") Long triggerRequestId);

  String setTenantContext(@Param("tenantId") String tenantId);

  JobMonitoringAlertCandidate selectCandidateForRecheck(JobMonitoringRecheckQuery query);

  int claimAlert(
      @Param("tenantId") String tenantId,
      @Param("jobInstanceId") Long jobInstanceId,
      @Param("violationType") String violationType);
}
