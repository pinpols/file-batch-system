package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.entity.JobMonitoringAlertCandidate;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 作业监控旁路查询与告警幂等声明；不更新 job_instance 生命周期字段。 */
public interface JobMonitoringMapper {

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
