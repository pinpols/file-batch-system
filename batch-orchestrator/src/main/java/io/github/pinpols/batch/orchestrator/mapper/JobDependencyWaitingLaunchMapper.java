package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.entity.JobDependencyWaitingLaunchEntity;
import io.github.pinpols.batch.orchestrator.domain.query.JobDependencyWaitBacklog;
import io.github.pinpols.batch.orchestrator.domain.query.JobDependencyWaitingLaunchSummary;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface JobDependencyWaitingLaunchMapper {

  enum WaitStatus {
    WAITING,
    RELEASED,
    CANCELLED
  }

  int insertIfAbsent(JobDependencyWaitingLaunchEntity entity);

  JobDependencyWaitingLaunchEntity selectByTenantAndRequestId(
      @Param("tenantId") String tenantId, @Param("requestId") String requestId);

  List<JobDependencyWaitingLaunchEntity> selectWaiting(
      @Param("tenantId") String tenantId, @Param("limit") int limit);

  int countWaitingForCalendarBizDate(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("bizDate") LocalDate bizDate);

  List<JobDependencyWaitingLaunchSummary> selectRecent(
      @Param("tenantId") String tenantId,
      @Param("waitStatus") String waitStatus,
      @Param("jobCode") String jobCode,
      @Param("limit") int limit);

  /** Low-cardinality aggregate across tenants for operational backlog metrics. */
  JobDependencyWaitBacklog selectBacklogMetrics();

  int recordCheck(
      @Param("tenantId") String tenantId,
      @Param("requestId") String requestId,
      @Param("reason") String reason);

  default int markReleased(String tenantId, String requestId) {
    return transitionStatus(
        tenantId, requestId, WaitStatus.WAITING.name(), WaitStatus.RELEASED.name());
  }

  int transitionStatus(
      @Param("tenantId") String tenantId,
      @Param("requestId") String requestId,
      @Param("expectedStatus") String expectedStatus,
      @Param("targetStatus") String targetStatus);

  int claimTimeoutAlert(
      @Param("tenantId") String tenantId,
      @Param("requestId") String requestId,
      @Param("alertedAt") Instant alertedAt);

  default int markCancelled(String tenantId, String requestId) {
    return transitionStatus(
        tenantId, requestId, WaitStatus.WAITING.name(), WaitStatus.CANCELLED.name());
  }
}
