package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayWaitingLaunchEntity;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface BatchDayWaitingLaunchMapper {

  int insert(BatchDayWaitingLaunchEntity entity);

  BatchDayWaitingLaunchEntity selectByTenantAndRequestId(
      @Param("tenantId") String tenantId, @Param("requestId") String requestId);

  List<BatchDayWaitingLaunchEntity> selectWaiting(
      @Param("tenantId") String tenantId, @Param("limit") int limit);

  List<BatchDayWaitingLaunchEntity> selectWaitingByCalendarBizDate(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("bizDate") LocalDate bizDate,
      @Param("limit") int limit);

  int markReleased(
      @Param("tenantId") String tenantId,
      @Param("requestId") String requestId,
      @Param("releasedBy") String releasedBy);
}
