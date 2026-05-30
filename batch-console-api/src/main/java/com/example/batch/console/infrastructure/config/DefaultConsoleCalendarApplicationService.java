package com.example.batch.console.infrastructure.config;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.model.PageRequest;
import com.example.batch.common.model.PageResponse;
import com.example.batch.common.utils.Guard;
import com.example.batch.console.application.config.ConsoleCalendarApplicationService;
import com.example.batch.console.mapper.BusinessCalendarMapper;
import com.example.batch.console.mapper.CalendarHolidayMapper;
import com.example.batch.console.domain.rbac.support.ConsoleTenantGuard;
import com.example.batch.console.web.request.file.CalendarSaveRequest;
import com.example.batch.console.web.request.file.HolidayImportRequest;
import com.example.batch.console.web.request.file.HolidaySaveRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** {@link ConsoleCalendarApplicationService} 的默认实现。 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleCalendarApplicationService implements ConsoleCalendarApplicationService {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String ERR_CALENDAR_NOT_FOUND = "calendar not found";
  private static final String KEY_CALENDAR_CODE = "calendar_code";

  private final BusinessCalendarMapper calendarMapper;
  private final CalendarHolidayMapper holidayMapper;
  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleConfigCacheInvalidationService cacheInvalidationService;

  @Override
  public PageResponse<Map<String, Object>> list(
      String tenantId, String calendarCode, Boolean enabled, Integer pageNo, Integer pageSize) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    PageRequest pageRequest = new PageRequest(pageNo, pageSize);
    long total = calendarMapper.countByQuery(resolved, calendarCode, enabled);
    List<Map<String, Object>> items =
        calendarMapper.selectByQuery(resolved, calendarCode, enabled, pageRequest);
    return new PageResponse<>(total, pageNo, pageSize, items);
  }

  @Override
  public Map<String, Object> create(CalendarSaveRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    long existing = calendarMapper.countByQuery(tenantId, request.getCalendarCode(), null);
    if (existing > 0) {
      throw BizException.of(
          ResultCode.CONFLICT,
          "error.common.conflict_detail",
          "calendar code already exists: " + request.getCalendarCode());
    }
    Map<String, Object> params = new HashMap<>();
    params.put("tenantId", tenantId);
    params.put("calendarCode", request.getCalendarCode());
    params.put("calendarName", request.getCalendarName());
    params.put("timezone", request.getTimezone());
    params.put(
        "holidayRollRule",
        request.getHolidayRollRule() == null ? "SKIP" : request.getHolidayRollRule());
    params.put(
        "catchUpPolicy", request.getCatchUpPolicy() == null ? "NONE" : request.getCatchUpPolicy());
    params.put(
        "catchUpMaxDays", request.getCatchUpMaxDays() == null ? 0 : request.getCatchUpMaxDays());
    params.put("enabled", request.getEnabled() != null && request.getEnabled());
    calendarMapper.insert(params);
    cacheInvalidationService.evictBusinessCalendar(tenantId, request.getCalendarCode());
    return calendarMapper.selectById(tenantId, ((Number) params.get("id")).longValue());
  }

  @Override
  public Map<String, Object> update(Long id, CalendarSaveRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    Map<String, Object> existing =
        Guard.requireFound(calendarMapper.selectById(tenantId, id), ERR_CALENDAR_NOT_FOUND);
    Map<String, Object> params = new HashMap<>();
    params.put("id", id);
    params.put("tenantId", tenantId);
    params.put("calendarName", request.getCalendarName());
    params.put("timezone", request.getTimezone());
    params.put(
        "holidayRollRule",
        request.getHolidayRollRule() == null ? "SKIP" : request.getHolidayRollRule());
    params.put(
        "catchUpPolicy", request.getCatchUpPolicy() == null ? "NONE" : request.getCatchUpPolicy());
    params.put(
        "catchUpMaxDays", request.getCatchUpMaxDays() == null ? 0 : request.getCatchUpMaxDays());
    calendarMapper.update(params);
    cacheInvalidationService.evictBusinessCalendar(
        tenantId, String.valueOf(existing.get(KEY_CALENDAR_CODE)));
    return calendarMapper.selectById(tenantId, id);
  }

  @Override
  public void toggle(Long id, String tenantId, Boolean enabled) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    int rows = calendarMapper.toggleEnabled(resolved, id, enabled);
    if (rows == 0) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.calendar.not_found");
    }
    Map<String, Object> calendar = calendarMapper.selectById(resolved, id);
    if (calendar != null) {
      cacheInvalidationService.evictBusinessCalendar(
          resolved, String.valueOf(calendar.get(KEY_CALENDAR_CODE)));
    }
  }

  @Override
  public List<Map<String, Object>> holidays(Long id, String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    Guard.requireFound(calendarMapper.selectById(resolved, id), ERR_CALENDAR_NOT_FOUND);
    return holidayMapper.selectByCalendarId(id);
  }

  @Override
  public void importHolidays(Long id, HolidayImportRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    Map<String, Object> calendar =
        Guard.requireFound(calendarMapper.selectById(tenantId, id), ERR_CALENDAR_NOT_FOUND);
    List<Map<String, Object>> list =
        request.getItems().stream()
            .map(
                item -> {
                  Map<String, Object> m = new HashMap<>();
                  // P0-2(2026-05-16): V92 后 calendar_holiday.tenant_id NOT NULL;
                  // batchInsert 取 #{item.tenantId},此前 map 漏写整条 INSERT 抛
                  // NOT NULL 违反,日历节假日导入 API 不可用。
                  m.put("tenantId", tenantId);
                  m.put("calendarId", id);
                  m.put("bizDate", item.getBizDate());
                  m.put("dayType", item.getDayType());
                  m.put("holidayName", item.getHolidayName());
                  m.put("description", item.getDescription());
                  return m;
                })
            .collect(Collectors.toList());
    holidayMapper.batchInsert(list);
    cacheInvalidationService.evictBusinessCalendar(
        tenantId, String.valueOf(calendar.get(KEY_CALENDAR_CODE)));
  }

  @Override
  public Map<String, Object> updateHoliday(Long id, Long holidayId, HolidaySaveRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    Map<String, Object> calendar =
        Guard.requireFound(calendarMapper.selectById(tenantId, id), ERR_CALENDAR_NOT_FOUND);
    Map<String, Object> existing =
        Guard.requireFound(
            holidayMapper.selectByCalendarIdAndId(id, holidayId), "holiday not found");
    // Mapper 层也带 parent calendar 条件，避免未来调用方绕过 service owner-check。
    requireHolidayBelongsToCalendar(existing, id);
    Map<String, Object> params = new HashMap<>();
    params.put("id", holidayId);
    params.put("calendarId", id);
    params.put("bizDate", request.getBizDate());
    params.put("dayType", request.getDayType());
    params.put("holidayName", request.getHolidayName());
    params.put("description", request.getDescription());
    holidayMapper.update(params);
    cacheInvalidationService.evictBusinessCalendar(
        tenantId, String.valueOf(calendar.get(KEY_CALENDAR_CODE)));
    return holidayMapper.selectByCalendarIdAndId(id, holidayId);
  }

  @Override
  public void deleteHoliday(Long id, Long holidayId, String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    Map<String, Object> calendar =
        Guard.requireFound(calendarMapper.selectById(resolved, id), ERR_CALENDAR_NOT_FOUND);
    Map<String, Object> existing =
        Guard.requireFound(
            holidayMapper.selectByCalendarIdAndId(id, holidayId), "holiday not found");
    requireHolidayBelongsToCalendar(existing, id);
    int rows = holidayMapper.deleteByCalendarIdAndId(id, holidayId);
    if (rows == 0) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.holiday.not_found");
    }
    cacheInvalidationService.evictBusinessCalendar(
        resolved, String.valueOf(calendar.get(KEY_CALENDAR_CODE)));
  }

  /**
   * P1-1:校验 holiday 的 calendar_id 与请求的 calendar id 一致。selectById 已 SELECT calendar_id as
   * "calendarId" (CalendarHolidayMapper.xml :18),取出后做 number 等值比较。不一致按 NOT_FOUND 报,不泄露归属信息。
   */
  private static void requireHolidayBelongsToCalendar(
      Map<String, Object> existing, Long calendarId) {
    Object existingCalId = existing.get("calendarId");
    if (existingCalId == null
        || !String.valueOf(existingCalId).equals(String.valueOf(calendarId))) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.holiday.not_found");
    }
  }
}
