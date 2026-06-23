package io.github.pinpols.batch.trigger.mapper;

import io.github.pinpols.batch.trigger.support.CalendarHolidayRule;
import io.github.pinpols.batch.trigger.support.TriggerCalendarConfig;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface BusinessCalendarMapper {

  TriggerCalendarConfig selectActiveByTenantAndCalendarCode(
      @Param("tenantId") String tenantId, @Param("calendarCode") String calendarCode);

  List<CalendarHolidayRule> selectHolidayRulesByCalendarId(@Param("calendarId") Long calendarId);
}
