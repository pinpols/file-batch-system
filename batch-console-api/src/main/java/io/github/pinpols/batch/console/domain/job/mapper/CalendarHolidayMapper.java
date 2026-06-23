package io.github.pinpols.batch.console.domain.job.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface CalendarHolidayMapper {

  List<Map<String, Object>> selectByCalendarId(@Param("calendarId") Long calendarId);

  Map<String, Object> selectById(@Param("id") Long id);

  Map<String, Object> selectByCalendarIdAndId(
      @Param("calendarId") Long calendarId, @Param("id") Long id);

  int insert(Map<String, Object> params);

  int batchInsert(@Param("list") List<Map<String, Object>> list);

  int update(Map<String, Object> params);

  int deleteByCalendarId(@Param("calendarId") Long calendarId);

  int deleteById(@Param("id") Long id);

  int deleteByCalendarIdAndId(@Param("calendarId") Long calendarId, @Param("id") Long id);
}
