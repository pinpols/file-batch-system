package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayWaitingLaunchEntity;
import io.github.pinpols.batch.orchestrator.domain.param.BatchDayWaitingLaunchCalendarQuery;
import io.github.pinpols.batch.orchestrator.domain.param.BatchDayWaitingLaunchTransitionParam;
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
    BatchDayWaitingLaunchCalendarQuery batchDayWaitingLaunchCalendarQuery =
        BatchDayWaitingLaunchCalendarQuery.builder()
            .tenantId(tenantId)
            .calendarCode(calendarCode)
            .bizDate(bizDate)
            .waitStatus(WaitStatus.WAITING.name())
            .limit(limit)
            .build();
    return selectByCalendarBizDateAndStatus(batchDayWaitingLaunchCalendarQuery);
  }

  List<BatchDayWaitingLaunchEntity> selectByCalendarBizDateAndStatus(
      BatchDayWaitingLaunchCalendarQuery param);

  default int markReleased(String tenantId, String requestId, String releasedBy) {
    BatchDayWaitingLaunchTransitionParam batchDayWaitingLaunchTransitionParam =
        BatchDayWaitingLaunchTransitionParam.builder()
            .tenantId(tenantId)
            .requestId(requestId)
            .releasedBy(releasedBy)
            .expectedStatus(WaitStatus.WAITING.name())
            .targetStatus(WaitStatus.RELEASED.name())
            .build();
    return transitionStatus(batchDayWaitingLaunchTransitionParam);
  }

  int transitionStatus(BatchDayWaitingLaunchTransitionParam param);
}
