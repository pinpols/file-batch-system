package com.example.batch.console.infrastructure.config;

import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.application.config.ConsoleBusinessCalendarExcelApplicationService;
import com.example.batch.console.infrastructure.excel.BusinessCalendarExcelWorkbookWriter;
import com.example.batch.console.mapper.BusinessCalendarMapper;
import com.example.batch.console.mapper.CalendarHolidayMapper;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/** 工作日历 Excel 模板与导出服务。 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleBusinessCalendarExcelApplicationService
    implements ConsoleBusinessCalendarExcelApplicationService {

  private static final String COL_CALENDAR_CODE = "calendar_code";

  private final ConsoleTenantGuard tenantGuard;
  private final BusinessCalendarMapper businessCalendarMapper;
  private final CalendarHolidayMapper calendarHolidayMapper;
  private final BusinessCalendarExcelWorkbookWriter workbookWriter;
  private final BatchDateTimeSupport dateTimeSupport;

  @Override
  public ResponseEntity<InputStreamResource> exportBusinessCalendars(String tenantId) {
    String resolvedTenantId = tenantGuard.resolveTenant(tenantId);
    List<Map<String, Object>> calendars =
        businessCalendarMapper.selectByQuery(resolvedTenantId, null, null, null);
    List<Map<String, Object>> allHolidays = new ArrayList<>();
    for (Map<String, Object> calendar : calendars) {
      Long calendarId = ((Number) calendar.get("id")).longValue();
      String calendarCode = (String) calendar.get("calendarCode");
      List<Map<String, Object>> holidays = calendarHolidayMapper.selectByCalendarId(calendarId);
      for (Map<String, Object> holiday : holidays) {
        holiday.put(COL_CALENDAR_CODE, calendarCode);
      }
      allHolidays.addAll(holidays);
    }
    byte[] workbookBytes = workbookWriter.writeMaintenanceWorkbook(calendars, allHolidays);
    String fileName =
        "business-calendar-"
            + resolvedTenantId
            + "-"
            + dateTimeSupport.currentFileTimestamp()
            + ".xlsx";
    return excelResponse(workbookBytes, fileName);
  }

  @Override
  public ResponseEntity<InputStreamResource> downloadTemplate() {
    return excelResponse(
        workbookWriter.writeMaintenanceWorkbook(List.of(), List.of()),
        "business-calendar-template.xlsx");
  }

  private ResponseEntity<InputStreamResource> excelResponse(byte[] workbookBytes, String fileName) {
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(fileName).build().toString())
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .body(new InputStreamResource(new ByteArrayInputStream(workbookBytes)));
  }
}
