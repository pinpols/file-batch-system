package io.github.pinpols.batch.console.domain.job.mapper;

import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.console.domain.job.param.BusinessCalendarInsertParam;
import io.github.pinpols.batch.console.domain.job.param.BusinessCalendarUpdateParam;
import io.github.pinpols.batch.console.domain.job.param.BusinessCalendarUpsertParam;
import io.github.pinpols.batch.console.domain.job.view.ConsoleJobConfigViews.ActiveCalendarRow;
import io.github.pinpols.batch.console.domain.job.view.ConsoleJobConfigViews.CalendarQueryRow;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface BusinessCalendarMapper {

  ActiveCalendarRow selectActiveByTenantAndCalendarCode(
      @Param("tenantId") String tenantId, @Param("calendarCode") String calendarCode);

  CalendarQueryRow selectByTenantAndCalendarCode(
      @Param("tenantId") String tenantId, @Param("calendarCode") String calendarCode);

  List<CalendarQueryRow> selectByQuery(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("enabled") Boolean enabled,
      @Param("pageRequest") PageRequest pageRequest);

  long countByQuery(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("enabled") Boolean enabled);

  CalendarQueryRow selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

  int upsertBusinessCalendar(BusinessCalendarUpsertParam param);

  int insert(BusinessCalendarInsertParam params);

  int update(BusinessCalendarUpdateParam params);

  int toggleEnabled(
      @Param("tenantId") String tenantId, @Param("id") Long id, @Param("enabled") Boolean enabled);
}
