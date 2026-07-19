package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.utils.Nullables;
import io.github.pinpols.batch.console.domain.job.mapper.BatchWindowMapper;
import io.github.pinpols.batch.console.domain.job.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.console.domain.job.mapper.CalendarHolidayMapper;
import io.github.pinpols.batch.console.domain.job.param.BatchWindowUpsertParam;
import io.github.pinpols.batch.console.domain.job.param.BusinessCalendarUpsertParam;
import io.github.pinpols.batch.console.domain.notification.mapper.AlertRoutingConfigMapper;
import io.github.pinpols.batch.console.domain.ops.mapper.ResourceQueueMapper;
import io.github.pinpols.batch.console.domain.param.AlertRoutingConfigUpsertParam;
import io.github.pinpols.batch.console.domain.param.ResourceQueueUpsertParam;
import io.github.pinpols.batch.console.domain.param.TenantQuotaPolicyUpsertParam;
import io.github.pinpols.batch.console.domain.rbac.mapper.TenantQuotaPolicyMapper;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.AlertRoutingSpec;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.BatchWindowSpec;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.BusinessCalendarSpec;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.ResourceQueueSpec;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.TenantQuotaPolicySpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 租户初始化中的资源、窗口、日历、配额和告警配置持久化协作者。 */
@Component
@RequiredArgsConstructor
final class TenantOperationalConfigApplySupport {

  private static final String KEY_ID = "id";

  private final ResourceQueueMapper resourceQueueMapper;
  private final BatchWindowMapper batchWindowMapper;
  private final BusinessCalendarMapper businessCalendarMapper;
  private final CalendarHolidayMapper calendarHolidayMapper;
  private final TenantQuotaPolicyMapper tenantQuotaPolicyMapper;
  private final AlertRoutingConfigMapper alertRoutingConfigMapper;

  Map<String, Object> findResourceQueue(String tenantId, ResourceQueueSpec spec) {
    return resourceQueueMapper.selectByUniqueKey(tenantId, spec.getQueueCode());
  }

  void upsertResourceQueue(String tenantId, ResourceQueueSpec spec, String operator) {
    ResourceQueueUpsertParam param = new ResourceQueueUpsertParam();
    param.setTenantId(tenantId);
    param.setQueueCode(spec.getQueueCode());
    param.setQueueName(spec.getQueueName());
    param.setQueueType(spec.getQueueType());
    param.setMaxRunningJobs(spec.getMaxRunningJobs());
    param.setMaxRunningPartitions(spec.getMaxRunningPartitions());
    param.setMaxQps(spec.getMaxQps());
    param.setWorkerGroup(spec.getWorkerGroup());
    param.setResourceTag(spec.getResourceTag());
    param.setPriorityPolicy(spec.getPriorityPolicy());
    param.setFairShareWeight(spec.getFairShareWeight());
    param.setEnabled(Nullables.coalesce(spec.getEnabled(), true));
    param.setDescription(spec.getDescription());
    param.setCreatedBy(operator);
    param.setUpdatedBy(operator);
    resourceQueueMapper.upsertResourceQueue(param);
  }

  Map<String, Object> findBatchWindow(String tenantId, BatchWindowSpec spec) {
    return batchWindowMapper.selectByUniqueKey(tenantId, spec.getWindowCode());
  }

  void upsertBatchWindow(String tenantId, BatchWindowSpec spec) {
    BatchWindowUpsertParam param = new BatchWindowUpsertParam();
    param.setTenantId(tenantId);
    param.setWindowCode(spec.getWindowCode());
    param.setWindowName(spec.getWindowName());
    param.setTimezone(Nullables.coalesce(spec.getTimezone(), CommonConstants.DEFAULT_TIMEZONE_ID));
    param.setStartTime(spec.getStartTime());
    param.setEndTime(spec.getEndTime());
    param.setEndStrategy(spec.getEndStrategy());
    param.setOutOfWindowAction(spec.getOutOfWindowAction());
    param.setAllowCrossDay(Nullables.coalesce(spec.getAllowCrossDay(), false));
    param.setEnabled(Nullables.coalesce(spec.getEnabled(), true));
    param.setDescription(spec.getDescription());
    batchWindowMapper.upsertBatchWindow(param);
  }

  Map<String, Object> findBusinessCalendar(String tenantId, BusinessCalendarSpec spec) {
    return businessCalendarMapper.selectActiveByTenantAndCalendarCode(
        tenantId, spec.getCalendarCode());
  }

  void upsertBusinessCalendar(
      String tenantId, BusinessCalendarSpec spec, String operator, Long existingId) {
    BusinessCalendarUpsertParam param = new BusinessCalendarUpsertParam();
    param.setTenantId(tenantId);
    param.setCalendarCode(spec.getCalendarCode());
    param.setCalendarName(spec.getCalendarName());
    param.setTimezone(Nullables.coalesce(spec.getTimezone(), CommonConstants.DEFAULT_TIMEZONE_ID));
    param.setHolidayRollRule(spec.getHolidayRollRule());
    param.setCatchUpPolicy(spec.getCatchUpPolicy());
    param.setCatchUpMaxDays(spec.getCatchUpMaxDays());
    param.setEnabled(Nullables.coalesce(spec.getEnabled(), true));
    param.setCreatedBy(operator);
    param.setUpdatedBy(operator);
    businessCalendarMapper.upsertBusinessCalendar(param);

    if (spec.getHolidays() == null || spec.getHolidays().isEmpty()) {
      return;
    }
    Map<String, Object> saved =
        businessCalendarMapper.selectActiveByTenantAndCalendarCode(
            tenantId, spec.getCalendarCode());
    if (saved == null) {
      return;
    }
    Long calendarId = ((Number) saved.get(KEY_ID)).longValue();
    if (existingId != null) {
      calendarHolidayMapper.deleteByCalendarId(calendarId);
    }
    List<Map<String, Object>> holidayRows = new ArrayList<>();
    for (String date : spec.getHolidays()) {
      Map<String, Object> row = new HashMap<>();
      row.put("calendar_id", calendarId);
      row.put("holiday_date", date);
      row.put("holiday_type", "PUBLIC_HOLIDAY");
      row.put("created_by", operator);
      holidayRows.add(row);
    }
    calendarHolidayMapper.batchInsert(holidayRows);
  }

  Map<String, Object> findQuotaPolicy(String tenantId, TenantQuotaPolicySpec spec) {
    return tenantQuotaPolicyMapper.selectByUniqueKey(tenantId, spec.getPolicyCode());
  }

  void upsertQuotaPolicy(String tenantId, TenantQuotaPolicySpec spec) {
    TenantQuotaPolicyUpsertParam param =
        TenantQuotaPolicyUpsertParam.builder()
            .tenantId(tenantId)
            .policyCode(spec.getPolicyCode())
            .maxRunningJobsPerTenant(spec.getMaxRunningJobsPerTenant())
            .maxPartitionsPerTenant(spec.getMaxPartitionsPerTenant())
            .maxQpsPerTenant(spec.getMaxQpsPerTenant())
            .fairShareWeight(spec.getFairShareWeight())
            .enabled(Nullables.coalesce(spec.getEnabled(), true))
            .description(spec.getDescription())
            .build();
    tenantQuotaPolicyMapper.upsertTenantQuotaPolicy(param);
  }

  Map<String, Object> findAlertRouting(String tenantId, AlertRoutingSpec spec) {
    return alertRoutingConfigMapper.selectByUniqueKey(tenantId, spec.getRouteCode());
  }

  void upsertAlertRouting(String tenantId, AlertRoutingSpec spec, String operator) {
    AlertRoutingConfigUpsertParam param = new AlertRoutingConfigUpsertParam();
    param.setTenantId(tenantId);
    param.setRouteCode(spec.getRouteCode());
    param.setRouteName(spec.getRouteName());
    param.setTeam(spec.getTeam());
    param.setAlertGroup(spec.getAlertGroup());
    param.setSeverity(spec.getSeverity());
    param.setReceiver(spec.getReceiver());
    param.setGroupBy(spec.getGroupBy());
    param.setGroupWaitSeconds(spec.getGroupWaitSeconds());
    param.setGroupIntervalSeconds(spec.getGroupIntervalSeconds());
    param.setRepeatIntervalSeconds(spec.getRepeatIntervalSeconds());
    param.setEnabled(Nullables.coalesce(spec.getEnabled(), true));
    param.setDescription(spec.getDescription());
    param.setCreatedBy(operator);
    param.setUpdatedBy(operator);
    alertRoutingConfigMapper.upsertAlertRoutingConfig(param);
  }
}
