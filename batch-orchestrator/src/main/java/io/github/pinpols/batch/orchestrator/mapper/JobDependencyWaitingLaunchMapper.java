package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.entity.JobDependencyWaitingLaunchEntity;
import io.github.pinpols.batch.orchestrator.domain.query.JobDependencyWaitBacklog;
import io.github.pinpols.batch.orchestrator.domain.query.JobDependencyWaitingLaunchSummary;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface JobDependencyWaitingLaunchMapper {

  String setTenantContext(@Param("tenantId") String tenantId);

  enum WaitStatus {
    WAITING,
    RELEASED,
    CANCELLED
  }

  int insertIfAbsent(JobDependencyWaitingLaunchEntity entity);

  JobDependencyWaitingLaunchEntity selectByTenantAndRequestId(
      @Param("tenantId") String tenantId, @Param("requestId") String requestId);

  JobDependencyWaitingLaunchEntity selectByTenantAndRequestIdForUpdate(
      @Param("tenantId") String tenantId, @Param("requestId") String requestId);

  /** 原请求锁内检查亲自创建的实例，保留T1已提交但T2未完成的恢复入口。 */
  boolean hasJobInstanceForRequest(
      @Param("tenantId") String tenantId, @Param("triggerRequestId") Long triggerRequestId);

  List<JobDependencyWaitingLaunchEntity> selectWaiting(
      @Param("tenantId") String tenantId, @Param("limit") int limit);

  List<String> selectRejectedForWorkflowProgression(
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

  /** 跨租户低基数运维积压指标汇总。 */
  JobDependencyWaitBacklog selectBacklogMetrics(@Param("tenantId") String tenantId);

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
