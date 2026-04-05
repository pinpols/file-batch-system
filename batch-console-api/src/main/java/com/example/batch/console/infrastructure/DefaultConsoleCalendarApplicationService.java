package com.example.batch.console.infrastructure;

import com.example.batch.console.application.ConsoleCalendarApplicationService;
import com.example.batch.console.mapper.BusinessCalendarMapper;
import com.example.batch.console.mapper.CalendarHolidayMapper;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.web.request.CalendarSaveRequest;
import com.example.batch.console.web.request.HolidayImportRequest;
import com.example.batch.console.web.request.HolidaySaveRequest;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.model.PageRequest;
import com.example.batch.common.model.PageResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * {@link ConsoleCalendarApplicationService} 的默认实现。
 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleCalendarApplicationService implements ConsoleCalendarApplicationService {

    private final BusinessCalendarMapper calendarMapper;
    private final CalendarHolidayMapper holidayMapper;
    private final ConsoleTenantGuard tenantGuard;
    private final ConsoleConfigCacheInvalidationService cacheInvalidationService;

    @Override
    public PageResponse<Map<String, Object>> list(String tenantId, String calendarCode, Boolean enabled,
                                                   Integer pageNo, Integer pageSize) {
        String resolved = tenantGuard.resolveTenant(tenantId);
        PageRequest pageRequest = new PageRequest(pageNo, pageSize);
        long total = calendarMapper.countByQuery(resolved, calendarCode, enabled);
        List<Map<String, Object>> items = calendarMapper.selectByQuery(resolved, calendarCode, enabled, pageRequest);
        return new PageResponse<>(total, pageNo, pageSize, items);
    }

    @Override
    public Map<String, Object> create(CalendarSaveRequest request) {
        String tenantId = tenantGuard.resolveTenant(request.getTenantId());
        long existing = calendarMapper.countByQuery(tenantId, request.getCalendarCode(), null);
        if (existing > 0) {
            throw new BizException(ResultCode.CONFLICT, "calendar code already exists: " + request.getCalendarCode());
        }
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("calendarCode", request.getCalendarCode());
        params.put("calendarName", request.getCalendarName());
        params.put("timezone", request.getTimezone());
        params.put("holidayRollRule", request.getHolidayRollRule() == null ? "SKIP" : request.getHolidayRollRule());
        params.put("catchUpPolicy", request.getCatchUpPolicy() == null ? "NONE" : request.getCatchUpPolicy());
        params.put("catchUpMaxDays", request.getCatchUpMaxDays() == null ? 0 : request.getCatchUpMaxDays());
        params.put("enabled", request.getEnabled() != null && request.getEnabled());
        calendarMapper.insert(params);
        cacheInvalidationService.evictBusinessCalendar(tenantId, request.getCalendarCode());
        return calendarMapper.selectById(tenantId, ((Number) params.get("id")).longValue());
    }

    @Override
    public Map<String, Object> update(Long id, CalendarSaveRequest request) {
        String tenantId = tenantGuard.resolveTenant(request.getTenantId());
        Map<String, Object> existing = calendarMapper.selectById(tenantId, id);
        if (existing == null) {
            throw new BizException(ResultCode.NOT_FOUND, "calendar not found");
        }
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        params.put("tenantId", tenantId);
        params.put("calendarName", request.getCalendarName());
        params.put("timezone", request.getTimezone());
        params.put("holidayRollRule", request.getHolidayRollRule() == null ? "SKIP" : request.getHolidayRollRule());
        params.put("catchUpPolicy", request.getCatchUpPolicy() == null ? "NONE" : request.getCatchUpPolicy());
        params.put("catchUpMaxDays", request.getCatchUpMaxDays() == null ? 0 : request.getCatchUpMaxDays());
        calendarMapper.update(params);
        cacheInvalidationService.evictBusinessCalendar(tenantId, String.valueOf(existing.get("calendar_code")));
        return calendarMapper.selectById(tenantId, id);
    }

    @Override
    public void toggle(Long id, String tenantId, Boolean enabled) {
        String resolved = tenantGuard.resolveTenant(tenantId);
        int rows = calendarMapper.toggleEnabled(resolved, id, enabled);
        if (rows == 0) {
            throw new BizException(ResultCode.NOT_FOUND, "calendar not found");
        }
        Map<String, Object> calendar = calendarMapper.selectById(resolved, id);
        if (calendar != null) {
            cacheInvalidationService.evictBusinessCalendar(resolved, String.valueOf(calendar.get("calendar_code")));
        }
    }

    @Override
    public List<Map<String, Object>> holidays(Long id, String tenantId) {
        String resolved = tenantGuard.resolveTenant(tenantId);
        Map<String, Object> calendar = calendarMapper.selectById(resolved, id);
        if (calendar == null) {
            throw new BizException(ResultCode.NOT_FOUND, "calendar not found");
        }
        return holidayMapper.selectByCalendarId(id);
    }

    @Override
    public void importHolidays(Long id, HolidayImportRequest request) {
        String tenantId = tenantGuard.resolveTenant(request.getTenantId());
        Map<String, Object> calendar = calendarMapper.selectById(tenantId, id);
        if (calendar == null) {
            throw new BizException(ResultCode.NOT_FOUND, "calendar not found");
        }
        List<Map<String, Object>> list = request.getItems().stream().map(item -> {
            Map<String, Object> m = new HashMap<>();
            m.put("calendarId", id);
            m.put("bizDate", item.getBizDate());
            m.put("dayType", item.getDayType());
            m.put("holidayName", item.getHolidayName());
            m.put("description", item.getDescription());
            return m;
        }).collect(Collectors.toList());
        holidayMapper.batchInsert(list);
        cacheInvalidationService.evictBusinessCalendar(tenantId, String.valueOf(calendar.get("calendar_code")));
    }

    @Override
    public Map<String, Object> updateHoliday(Long id, Long holidayId, HolidaySaveRequest request) {
        String tenantId = tenantGuard.resolveTenant(request.getTenantId());
        Map<String, Object> calendar = calendarMapper.selectById(tenantId, id);
        if (calendar == null) {
            throw new BizException(ResultCode.NOT_FOUND, "calendar not found");
        }
        Map<String, Object> existing = holidayMapper.selectById(holidayId);
        if (existing == null) {
            throw new BizException(ResultCode.NOT_FOUND, "holiday not found");
        }
        Map<String, Object> params = new HashMap<>();
        params.put("id", holidayId);
        params.put("bizDate", request.getBizDate());
        params.put("dayType", request.getDayType());
        params.put("holidayName", request.getHolidayName());
        params.put("description", request.getDescription());
        holidayMapper.update(params);
        cacheInvalidationService.evictBusinessCalendar(tenantId, String.valueOf(calendar.get("calendar_code")));
        return holidayMapper.selectById(holidayId);
    }

    @Override
    public void deleteHoliday(Long id, Long holidayId, String tenantId) {
        String resolved = tenantGuard.resolveTenant(tenantId);
        Map<String, Object> calendar = calendarMapper.selectById(resolved, id);
        if (calendar == null) {
            throw new BizException(ResultCode.NOT_FOUND, "calendar not found");
        }
        int rows = holidayMapper.deleteById(holidayId);
        if (rows == 0) {
            throw new BizException(ResultCode.NOT_FOUND, "holiday not found");
        }
        cacheInvalidationService.evictBusinessCalendar(resolved, String.valueOf(calendar.get("calendar_code")));
    }
}
