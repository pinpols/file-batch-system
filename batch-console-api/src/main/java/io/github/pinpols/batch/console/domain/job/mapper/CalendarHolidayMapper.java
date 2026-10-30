package io.github.pinpols.batch.console.domain.job.mapper;

import io.github.pinpols.batch.console.domain.job.param.CalendarHolidayBatchInsertParam;
import io.github.pinpols.batch.console.domain.job.param.CalendarHolidayBatchUpsertParam;
import io.github.pinpols.batch.console.domain.job.param.CalendarHolidayUpdateParam;
import io.github.pinpols.batch.console.domain.job.view.ConsoleJobConfigViews.CalendarHolidayRow;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface CalendarHolidayMapper {

  List<CalendarHolidayRow> selectByCalendarId(@Param("calendarId") Long calendarId);

  CalendarHolidayRow selectByCalendarIdAndId(
      @Param("calendarId") Long calendarId, @Param("id") Long id);

  int batchInsert(@Param("list") List<CalendarHolidayBatchInsertParam> list);

  int upsertBatch(@Param("list") List<CalendarHolidayBatchUpsertParam> list);

  int update(CalendarHolidayUpdateParam params);

  int deleteByCalendarId(@Param("calendarId") Long calendarId);

  int deleteByCalendarIdAndId(@Param("calendarId") Long calendarId, @Param("id") Long id);
}
