package com.example.batch.console.domain.job.application;

import com.example.batch.common.model.PageResponse;
import com.example.batch.console.domain.job.web.request.CalendarSaveRequest;
import com.example.batch.console.domain.job.web.request.HolidayImportRequest;
import com.example.batch.console.domain.job.web.request.HolidaySaveRequest;
import java.util.List;
import java.util.Map;

/** 业务日历应用服务：管理日历及节假日的 CRUD 操作。 */
public interface ConsoleCalendarApplicationService {

  PageResponse<Map<String, Object>> list(
      String tenantId, String calendarCode, Boolean enabled, Integer pageNo, Integer pageSize);

  Map<String, Object> create(CalendarSaveRequest request);

  Map<String, Object> update(Long id, CalendarSaveRequest request);

  void toggle(Long id, String tenantId, Boolean enabled);

  List<Map<String, Object>> holidays(Long id, String tenantId);

  void importHolidays(Long id, HolidayImportRequest request);

  Map<String, Object> updateHoliday(Long id, Long holidayId, HolidaySaveRequest request);

  void deleteHoliday(Long id, Long holidayId, String tenantId);
}
