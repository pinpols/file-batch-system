package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.model.PageResponse;
import com.example.batch.console.application.ConsoleCalendarApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.Idempotent;
import com.example.batch.console.web.request.file.CalendarSaveRequest;
import com.example.batch.console.web.request.file.HolidayImportRequest;
import com.example.batch.console.web.request.file.HolidaySaveRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/console/calendars")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@RequiredArgsConstructor
@Idempotent
public class ConsoleCalendarController {

  private final ConsoleCalendarApplicationService calendarApplicationService;
  private final ConsoleResponseFactory responseFactory;

  @GetMapping
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_CONFIG_ADMIN')")
  public CommonResponse<PageResponse<Map<String, Object>>> list(
      @RequestParam("tenantId") String tenantId,
      @RequestParam(value = "calendarCode", required = false) String calendarCode,
      @RequestParam(value = "enabled", required = false) Boolean enabled,
      @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
      @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize) {
    return responseFactory.success(
        calendarApplicationService.list(tenantId, calendarCode, enabled, pageNo, pageSize));
  }

  @PostMapping
  public CommonResponse<Map<String, Object>> create(
      @Valid @RequestBody CalendarSaveRequest request) {
    return responseFactory.success(calendarApplicationService.create(request));
  }

  @PutMapping("/{id}")
  public CommonResponse<Map<String, Object>> update(
      @PathVariable Long id, @Valid @RequestBody CalendarSaveRequest request) {
    return responseFactory.success(calendarApplicationService.update(id, request));
  }

  @PostMapping("/{id}/toggle")
  public CommonResponse<Void> toggle(
      @PathVariable Long id,
      @RequestParam("tenantId") String tenantId,
      @RequestParam("enabled") Boolean enabled) {
    calendarApplicationService.toggle(id, tenantId, enabled);
    return responseFactory.success(null);
  }

  @GetMapping("/{id}/holidays")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_CONFIG_ADMIN')")
  public CommonResponse<List<Map<String, Object>>> holidays(
      @PathVariable Long id, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(calendarApplicationService.holidays(id, tenantId));
  }

  @PostMapping("/{id}/holidays")
  public CommonResponse<Void> importHolidays(
      @PathVariable Long id, @Valid @RequestBody HolidayImportRequest request) {
    calendarApplicationService.importHolidays(id, request);
    return responseFactory.success(null);
  }

  @PutMapping("/{id}/holidays/{holidayId}")
  public CommonResponse<Map<String, Object>> updateHoliday(
      @PathVariable Long id,
      @PathVariable Long holidayId,
      @Valid @RequestBody HolidaySaveRequest request) {
    return responseFactory.success(
        calendarApplicationService.updateHoliday(id, holidayId, request));
  }

  @DeleteMapping("/{id}/holidays/{holidayId}")
  public CommonResponse<Void> deleteHoliday(
      @PathVariable Long id,
      @PathVariable Long holidayId,
      @RequestParam("tenantId") String tenantId) {
    calendarApplicationService.deleteHoliday(id, holidayId, tenantId);
    return responseFactory.success(null);
  }
}
