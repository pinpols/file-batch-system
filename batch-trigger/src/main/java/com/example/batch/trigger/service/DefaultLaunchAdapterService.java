package com.example.batch.trigger.service;

import com.example.batch.common.config.BatchTimezoneProvider;
import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.CatchUpPolicyType;
import com.example.batch.common.enums.ScheduleType;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.trigger.domain.command.ScheduledTriggerCommand;
import com.example.batch.trigger.domain.command.TriggerLaunchCommand;
import com.example.batch.trigger.support.CalendarBizDateDefinition;
import com.example.batch.trigger.wheel.CronExpressionAdapter;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 将触发命令翻译为 {@link LaunchRequest} 的适配器，屏蔽 API 触发与定时触发的参数差异。
 *
 * <ul>
 *   <li>{@link #fromApiRequest} — 直接透传 API 请求字段，bizDate 由调用方提供。
 *   <li>{@link #fromScheduledTrigger} — Quartz 触发路径：bizDate 由 {@link CalendarBizDateResolver} 根据
 *       fireTime + 日历配置（时区、cutoffTime、节假日）计算得出； {@code bizDate=null} 表示当天是节假日且
 *       rollRule=SKIP，调用方据此跳过本次调度； params 中额外写入 scheduleType、catchUp、catchUpApprovalRequired 等元数据，
 *       供 Orchestrator 记录审计和决策 catch-up 流程。
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultLaunchAdapterService implements LaunchAdapterService {

  private final CalendarBizDateResolver calendarBizDateResolver;
  private final BatchTimezoneProvider timezoneProvider;
  private final CronExpressionAdapter cronAdapter;

  @Override
  public LaunchRequest fromApiRequest(TriggerLaunchCommand command) {
    var request = command.request();
    // V94: API/MANUAL 触发的 data_interval 由调用方显式提供, 没传则 null (worker 走 bizDate 兜底)
    return LaunchRequest.builder()
        .tenantId(request.getTenantId())
        .jobCode(request.getJobCode())
        .bizDate(request.getBizDate())
        .triggerType(resolveTriggerType(command))
        .requestId(command.requestId())
        .traceId(command.traceId())
        .params(request.getParams())
        .dataIntervalStart(request.getDataIntervalStart())
        .dataIntervalEnd(request.getDataIntervalEnd())
        .build();
  }

  @Override
  public LaunchRequest fromScheduledTrigger(
      ScheduledTriggerCommand command, CalendarBizDateDefinition calendar) {
    var descriptor = command.descriptor();
    // timezone 未配置时 fallback 到平台默认（batch.timezone.default-zone），而非 JVM default。
    // 错误配置不应阻断所有调度触发，但会导致 bizDate 偏移，运维应在 Job 定义上明确填写 timezone。
    ZoneId zoneId = timezoneProvider.resolveOrDefault(descriptor.getTimezone());
    // bizDate=null 表示日历判断为节假日且 rollRule=SKIP，调用方据此跳过本次调度
    LocalDate bizDate = calendarBizDateResolver.resolve(command.fireTime(), zoneId, calendar);
    // triggerType 为 null 时说明来自普通 Quartz 触发，catchUp 触发会被显式设置为 CATCH_UP
    TriggerType triggerType =
        command.triggerType() == null ? TriggerType.SCHEDULED : command.triggerType();
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("scheduleType", descriptor.getScheduleType());
    params.put("scheduleExpression", descriptor.getScheduleExpression());
    params.put("triggerMode", descriptor.getTriggerMode());
    params.put("calendarCode", descriptor.getCalendarCode());
    params.put("catchUpPolicy", descriptor.getCatchUpPolicy());
    params.put("scheduledAt", command.fireTime().toString());
    // catchUp / catchUpApprovalRequired 写入 params 供 Orchestrator 决策 catch-up 审批流程
    params.put("catchUp", TriggerType.CATCH_UP == triggerType);
    params.put(
        "catchUpApprovalRequired",
        CatchUpPolicyType.MANUAL_APPROVAL.code().equalsIgnoreCase(descriptor.getCatchUpPolicy()));
    // V94: 计算 [thisFireAt, nextFireAt) 半开区间, 业务可拼 SQL WHERE update_time >= :start AND update_time <
    // :end
    Instant nextFireAt =
        resolveNextFireAt(
            descriptor.getScheduleType(),
            descriptor.getScheduleExpression(),
            zoneId,
            command.fireTime());
    return LaunchRequest.builder()
        .tenantId(descriptor.getTenantId())
        .jobCode(descriptor.getJobCode())
        .bizDate(bizDate)
        .triggerType(triggerType)
        .requestId(command.requestId())
        .traceId(command.traceId())
        .params(params)
        .dataIntervalStart(command.fireTime())
        .dataIntervalEnd(nextFireAt)
        .build();
  }

  /**
   * V94: 算下一次 fire 时刻, 用作 data_interval_end. 失败 (cron 非法 / scheduleType 未识别) 时返 null, 上游 instance 落
   * NULL data_interval, 由 worker 走 bizDate 兜底; 不阻断 launch.
   */
  private Instant resolveNextFireAt(
      String scheduleType, String scheduleExpression, ZoneId zoneId, Instant fireAt) {
    if (scheduleType == null || scheduleExpression == null || scheduleExpression.isBlank()) {
      return null;
    }
    String type = scheduleType.trim().toUpperCase(Locale.ROOT);
    try {
      if (ScheduleType.CRON.code().equals(type)) {
        return cronAdapter.next(scheduleExpression, zoneId, fireAt);
      }
      if (ScheduleType.FIXED_RATE.code().equals(type)) {
        // FIXED_RATE 表达式格式: 纯数字秒 (如 "60") 或 ISO duration (如 "PT5M"), 与 wheel scheduler 对齐
        Duration interval = parseFixedRateInterval(scheduleExpression);
        return interval == null ? null : fireAt.plus(interval);
      }
      // MANUAL / 其他类型不算 interval, 走 worker bizDate 兜底
      return null;
    } catch (RuntimeException ex) {
      log.warn(
          "data_interval next-fire computation failed: scheduleType={}, expr={}, error={}",
          scheduleType,
          scheduleExpression,
          ex.getMessage());
      return null;
    }
  }

  private static Duration parseFixedRateInterval(String expr) {
    String trimmed = expr.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    // 纯数字 → 秒
    if (trimmed.chars().allMatch(Character::isDigit)) {
      long seconds = Long.parseLong(trimmed);
      return seconds <= 0 ? null : Duration.ofSeconds(seconds);
    }
    // ISO-8601 (PT5M / PT1H / PT30S 等)
    try {
      return Duration.parse(trimmed.toUpperCase(Locale.ROOT));
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  @Override
  public TriggerType resolveTriggerType(TriggerLaunchCommand command) {
    // API 触发不要求调用方传 triggerType，null 时默认 API 以简化客户端接入
    return command.request().getTriggerType() == null
        ? TriggerType.API
        : command.request().getTriggerType();
  }
}
