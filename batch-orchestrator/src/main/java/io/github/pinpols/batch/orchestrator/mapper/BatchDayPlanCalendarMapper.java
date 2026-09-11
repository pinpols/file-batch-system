package io.github.pinpols.batch.orchestrator.mapper;

import java.time.LocalDate;
import org.apache.ibatis.annotations.Param;

/** 批量日计划演练使用的日历日类型只读查询。 */
public interface BatchDayPlanCalendarMapper {

  String selectEffectiveDayType(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("bizDate") LocalDate bizDate);
}
