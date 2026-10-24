package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.utils.CodeNormalizer;
import io.github.pinpols.batch.common.utils.Nullables;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.AlertRoutingSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.BatchWindowSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.BusinessCalendarSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.ResourceQueueSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.TenantQuotaPolicySpec;
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
    String queueCode = CodeNormalizer.toConfigFormOrNull(spec.getQueueCode());
    return resourceQueueMapper.selectByUniqueKey(tenantId, queueCode);
  }

  void upsertResourceQueue(String tenantId, ResourceQueueSpec spec, String operator) {
    ResourceQueueUpsertParam param = new ResourceQueueUpsertParam();
    param.setTenantId(tenantId);
    param.setQueueCode(CodeNormalizer.toConfigFormOrNull(spec.getQueueCode()));
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
    String windowCode = CodeNormalizer.toConfigFormOrNull(spec.getWindowCode());
    return batchWindowMapper.selectByUniqueKey(tenantId, windowCode);
  }

  void upsertBatchWindow(String tenantId, BatchWindowSpec spec) {
    BatchWindowUpsertParam param = new BatchWindowUpsertParam();
    param.setTenantId(tenantId);
    param.setWindowCode(CodeNormalizer.toConfigFormOrNull(spec.getWindowCode()));
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
    String calendarCode = CodeNormalizer.toConfigFormOrNull(spec.getCalendarCode());
    return businessCalendarMapper.selectActiveByTenantAndCalendarCode(tenantId, calendarCode);
  }

  void upsertBusinessCalendar(
      String tenantId, BusinessCalendarSpec spec, String operator, Long existingId) {
    BusinessCalendarUpsertParam param = new BusinessCalendarUpsertParam();
    param.setTenantId(tenantId);
    String calendarCode = CodeNormalizer.toConfigFormOrNull(spec.getCalendarCode());
    param.setCalendarCode(calendarCode);
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
        businessCalendarMapper.selectActiveByTenantAndCalendarCode(tenantId, calendarCode);
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
      row.put("tenantId", tenantId);
      row.put("calendarId", calendarId);
      row.put("bizDate", date);
      row.put("dayType", "HOLIDAY");
      row.put("holidayName", null);
      row.put("description", null);
      holidayRows.add(row);
    }
    calendarHolidayMapper.batchInsert(holidayRows);
  }

  Map<String, Object> findQuotaPolicy(String tenantId, TenantQuotaPolicySpec spec) {
    String policyCode = CodeNormalizer.toConfigFormOrNull(spec.getPolicyCode());
    return tenantQuotaPolicyMapper.selectByUniqueKey(tenantId, policyCode);
  }

  void upsertQuotaPolicy(String tenantId, TenantQuotaPolicySpec spec) {
    TenantQuotaPolicyUpsertParam param = TenantQuotaPolicyUpsertParam.builder()
        .tenantId(tenantId)
        .policyCode(CodeNormalizer.toConfigFormOrNull(spec.getPolicyCode()))
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
    String routeCode = CodeNormalizer.toConfigFormOrNull(spec.getRouteCode());
    return alertRoutingConfigMapper.selectByUniqueKey(tenantId, routeCode);
  }

  void upsertAlertRouting(String tenantId, AlertRoutingSpec spec, String operator) {
    AlertRoutingConfigUpsertParam param = new AlertRoutingConfigUpsertParam();
    param.setTenantId(tenantId);
    param.setRouteCode(CodeNormalizer.toConfigFormOrNull(spec.getRouteCode()));
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
