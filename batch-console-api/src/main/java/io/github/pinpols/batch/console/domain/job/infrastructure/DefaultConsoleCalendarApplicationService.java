package io.github.pinpols.batch.console.domain.job.infrastructure;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.console.application.config.ConsoleConfigCacheInvalidationService;
import io.github.pinpols.batch.console.domain.job.application.ConsoleCalendarApplicationService;
import io.github.pinpols.batch.console.domain.job.application.contract.request.CalendarSaveRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.request.HolidayImportRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.request.HolidaySaveRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleCalendarResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleHolidayResponse;
import io.github.pinpols.batch.console.domain.job.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.console.domain.job.mapper.CalendarHolidayMapper;
import io.github.pinpols.batch.console.domain.job.param.BusinessCalendarInsertParam;
import io.github.pinpols.batch.console.domain.job.param.BusinessCalendarUpdateParam;
import io.github.pinpols.batch.console.domain.job.param.CalendarHolidayBatchInsertParam;
import io.github.pinpols.batch.console.domain.job.param.CalendarHolidayUpdateParam;
import io.github.pinpols.batch.console.domain.job.support.BusinessCalendarTimezone;
import io.github.pinpols.batch.console.domain.job.view.ConsoleJobConfigViews.CalendarHolidayRow;
import io.github.pinpols.batch.console.domain.job.view.ConsoleJobConfigViews.CalendarQueryRow;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** {@link ConsoleCalendarApplicationService} 的默认实现。 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleCalendarApplicationService implements ConsoleCalendarApplicationService {

  private static final String ERR_CALENDAR_NOT_FOUND = "calendar not found";

  private final BusinessCalendarMapper calendarMapper;
  private final CalendarHolidayMapper holidayMapper;
  private final TenantIdResolver tenantGuard;
  private final ConsoleConfigCacheInvalidationService cacheInvalidationService;

  @Override
  public PageResponse<ConsoleCalendarResponse> list(
      String tenantId, String calendarCode, Boolean enabled, Integer pageNo, Integer pageSize) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    PageRequest pageRequest = new PageRequest(pageNo, pageSize);
    long total = calendarMapper.countByQuery(resolved, calendarCode, enabled);
    List<ConsoleCalendarResponse> items =
        calendarMapper.selectByQuery(resolved, calendarCode, enabled, pageRequest).stream()
            .map(ConsoleCalendarResponse::from)
            .toList();
    return new PageResponse<>(total, pageNo, pageSize, items);
  }

  @Override
  public ConsoleCalendarResponse create(CalendarSaveRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    BusinessCalendarTimezone.requireValid(request.getTimezone());
    long existing = calendarMapper.countByQuery(tenantId, request.getCalendarCode(), null);
    if (existing > 0) {
      throw BizException.of(
          ResultCode.CONFLICT,
          ResultCode.CONFLICT.detailKey(),
          "calendar code already exists: " + request.getCalendarCode());
    }
    BusinessCalendarInsertParam params = BusinessCalendarInsertParam.builder()
        .tenantId(tenantId)
        .calendarCode(request.getCalendarCode())
        .calendarName(request.getCalendarName())
        .timezone(request.getTimezone())
        .holidayRollRule(
            EmptyChecks.isNull(request.getHolidayRollRule())
                ? "SKIP"
                : request.getHolidayRollRule())
        .catchUpPolicy(
            EmptyChecks.isNull(request.getCatchUpPolicy()) ? "NONE" : request.getCatchUpPolicy())
        .catchUpMaxDays(
            EmptyChecks.isNull(request.getCatchUpMaxDays()) ? 0 : request.getCatchUpMaxDays())
        .enabled(EmptyChecks.isNotNull(request.getEnabled()) && request.getEnabled())
        .description(null)
        .build();
    calendarMapper.insert(params);
    cacheInvalidationService.evictBusinessCalendar(tenantId, request.getCalendarCode());
    long calendarId = params.getId();
    return ConsoleCalendarResponse.from(calendarMapper.selectById(tenantId, calendarId));
  }

  @Override
  public ConsoleCalendarResponse update(Long id, CalendarSaveRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    BusinessCalendarTimezone.requireValid(request.getTimezone());
    CalendarQueryRow existingRow = calendarMapper.selectById(tenantId, id);
    CalendarQueryRow existing = Guard.requireFound(existingRow, ERR_CALENDAR_NOT_FOUND);
    // 维护请求不提供说明字段，保留配置包等入口写入的既有说明。
    BusinessCalendarUpdateParam params = BusinessCalendarUpdateParam.builder()
        .id(id)
        .tenantId(tenantId)
        .calendarName(request.getCalendarName())
        .timezone(request.getTimezone())
        .holidayRollRule(
            EmptyChecks.isNull(request.getHolidayRollRule())
                ? "SKIP"
                : request.getHolidayRollRule())
        .catchUpPolicy(
            EmptyChecks.isNull(request.getCatchUpPolicy()) ? "NONE" : request.getCatchUpPolicy())
        .catchUpMaxDays(
            EmptyChecks.isNull(request.getCatchUpMaxDays()) ? 0 : request.getCatchUpMaxDays())
        .description(existing.description())
        .build();
    calendarMapper.update(params);
    cacheInvalidationService.evictBusinessCalendar(tenantId, existing.calendarCode());
    return ConsoleCalendarResponse.from(calendarMapper.selectById(tenantId, id));
  }

  @Override
  public void toggle(Long id, String tenantId, Boolean enabled) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    int rows = calendarMapper.toggleEnabled(resolved, id, enabled);
    if (rows == 0) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.calendar.not_found");
    }
    CalendarQueryRow calendar = calendarMapper.selectById(resolved, id);
    if (calendar != null) {
      cacheInvalidationService.evictBusinessCalendar(resolved, calendar.calendarCode());
    }
  }

  @Override
  public List<ConsoleHolidayResponse> holidays(Long id, String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    CalendarQueryRow calendar = calendarMapper.selectById(resolved, id);
    Guard.requireFound(calendar, ERR_CALENDAR_NOT_FOUND);
    return holidayMapper.selectByCalendarId(id).stream()
        .map(ConsoleHolidayResponse::from)
        .toList();
  }

  @Override
  public void importHolidays(Long id, HolidayImportRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    CalendarQueryRow calendar = calendarMapper.selectById(tenantId, id);
    Guard.requireFound(calendar, ERR_CALENDAR_NOT_FOUND);
    List<CalendarHolidayBatchInsertParam> list = request.getItems().stream()
        .map(item -> CalendarHolidayBatchInsertParam.builder()
            .tenantId(tenantId)
            .calendarId(id)
            .bizDate(item.getBizDate())
            .dayType(item.getDayType())
            .holidayName(item.getHolidayName())
            .description(item.getDescription())
            .build())
        .collect(Collectors.toList());
    holidayMapper.batchInsert(list);
    cacheInvalidationService.evictBusinessCalendar(tenantId, calendar.calendarCode());
  }

  @Override
  public ConsoleHolidayResponse updateHoliday(Long id, Long holidayId, HolidaySaveRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    CalendarQueryRow calendar = calendarMapper.selectById(tenantId, id);
    Guard.requireFound(calendar, ERR_CALENDAR_NOT_FOUND);
    CalendarHolidayRow existing = holidayMapper.selectByCalendarIdAndId(id, holidayId);
    Guard.requireFound(existing, "holiday not found");
    // Mapper 层也带 parent calendar 条件，避免未来调用方绕过 service owner-check。
    requireHolidayBelongsToCalendar(existing, id);
    CalendarHolidayUpdateParam params = CalendarHolidayUpdateParam.builder()
        .id(holidayId)
        .calendarId(id)
        .bizDate(request.getBizDate())
        .dayType(request.getDayType())
        .holidayName(request.getHolidayName())
        .description(request.getDescription())
        .build();
    holidayMapper.update(params);
    cacheInvalidationService.evictBusinessCalendar(tenantId, calendar.calendarCode());
    return ConsoleHolidayResponse.from(holidayMapper.selectByCalendarIdAndId(id, holidayId));
  }

  @Override
  public void deleteHoliday(Long id, Long holidayId, String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    CalendarQueryRow calendar = calendarMapper.selectById(resolved, id);
    Guard.requireFound(calendar, ERR_CALENDAR_NOT_FOUND);
    CalendarHolidayRow existing = holidayMapper.selectByCalendarIdAndId(id, holidayId);
    Guard.requireFound(existing, "holiday not found");
    requireHolidayBelongsToCalendar(existing, id);
    int rows = holidayMapper.deleteByCalendarIdAndId(id, holidayId);
    if (rows == 0) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.holiday.not_found");
    }
    cacheInvalidationService.evictBusinessCalendar(resolved, calendar.calendarCode());
  }

  /**
   * P1-1:校验 holiday 的 calendar_id 与请求的 calendar id 一致。具名行投影后 calendarId 为强类型
   * Long（calendar_id 为 NOT NULL 列，不存在历史 map 缺键场景）；不一致按 NOT_FOUND 报，不泄露归属信息。
   */
  private static void requireHolidayBelongsToCalendar(
      CalendarHolidayRow existing, Long calendarId) {
    if (!Objects.equals(existing.calendarId(), calendarId)) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.holiday.not_found");
    }
  }
}
