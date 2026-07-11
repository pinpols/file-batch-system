package io.github.pinpols.batch.console.domain.job.application;

import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.console.domain.job.web.request.CalendarSaveRequest;
import io.github.pinpols.batch.console.domain.job.web.request.HolidayImportRequest;
import io.github.pinpols.batch.console.domain.job.web.request.HolidaySaveRequest;
import io.github.pinpols.batch.console.domain.job.web.response.ConsoleCalendarResponse;
import io.github.pinpols.batch.console.domain.job.web.response.ConsoleHolidayResponse;
import java.util.List;

/** 业务日历应用服务：管理日历及节假日的 CRUD 操作。 */
public interface ConsoleCalendarApplicationService {

  PageResponse<ConsoleCalendarResponse> list(
      String tenantId, String calendarCode, Boolean enabled, Integer pageNo, Integer pageSize);

  ConsoleCalendarResponse create(CalendarSaveRequest request);

  ConsoleCalendarResponse update(Long id, CalendarSaveRequest request);

  void toggle(Long id, String tenantId, Boolean enabled);

  List<ConsoleHolidayResponse> holidays(Long id, String tenantId);

  void importHolidays(Long id, HolidayImportRequest request);

  ConsoleHolidayResponse updateHoliday(Long id, Long holidayId, HolidaySaveRequest request);

  void deleteHoliday(Long id, Long holidayId, String tenantId);
}
