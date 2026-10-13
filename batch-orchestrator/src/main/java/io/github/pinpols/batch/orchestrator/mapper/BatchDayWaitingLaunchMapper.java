package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayWaitingLaunchEntity;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface BatchDayWaitingLaunchMapper {

  enum WaitStatus {
    WAITING,
    RELEASED
  }

  default int insert(BatchDayWaitingLaunchEntity entity) {
    return insertWithStatus(entity, WaitStatus.WAITING.name());
  }

  int insertWithStatus(
      @Param("entity") BatchDayWaitingLaunchEntity entity,
      @Param("initialStatus") String initialStatus);

  BatchDayWaitingLaunchEntity selectByTenantAndRequestId(
      @Param("tenantId") String tenantId, @Param("requestId") String requestId);

  default List<BatchDayWaitingLaunchEntity> selectWaiting(String tenantId, int limit) {
    return selectByStatus(tenantId, WaitStatus.WAITING.name(), limit);
  }

  List<BatchDayWaitingLaunchEntity> selectByStatus(
      @Param("tenantId") String tenantId,
      @Param("waitStatus") String waitStatus,
      @Param("limit") int limit);

  default List<BatchDayWaitingLaunchEntity> selectWaitingByCalendarBizDate(
      String tenantId, String calendarCode, LocalDate bizDate, int limit) {
    return selectByCalendarBizDateAndStatus(
        tenantId, calendarCode, bizDate, WaitStatus.WAITING.name(), limit);
  }

  List<BatchDayWaitingLaunchEntity> selectByCalendarBizDateAndStatus(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("bizDate") LocalDate bizDate,
      @Param("waitStatus") String waitStatus,
      @Param("limit") int limit);

  default int markReleased(String tenantId, String requestId, String releasedBy) {
    return transitionStatus(
        tenantId, requestId, releasedBy, WaitStatus.WAITING.name(), WaitStatus.RELEASED.name());
  }

  int transitionStatus(
      @Param("tenantId") String tenantId,
      @Param("requestId") String requestId,
      @Param("releasedBy") String releasedBy,
      @Param("expectedStatus") String expectedStatus,
      @Param("targetStatus") String targetStatus);
}
