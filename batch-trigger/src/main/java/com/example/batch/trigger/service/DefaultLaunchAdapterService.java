package com.example.batch.trigger.service;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.CatchUpPolicyType;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.trigger.domain.command.ScheduledTriggerCommand;
import com.example.batch.trigger.domain.command.TriggerLaunchCommand;
import com.example.batch.trigger.support.CalendarBizDateDefinition;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 将触发命令翻译为 {@link LaunchRequest} 的适配器，屏蔽 API 触发与定时触发的参数差异。
 *
 * <ul>
 *   <li>{@link #fromApiRequest} — 直接透传 API 请求字段，bizDate 由调用方提供。
 *   <li>{@link #fromScheduledTrigger} — Quartz 触发路径：bizDate 由 {@link CalendarBizDateResolver}
 *       根据 fireTime + 日历配置（时区、cutoffTime、节假日）计算得出；
 *       {@code bizDate=null} 表示当天是节假日且 rollRule=SKIP，调用方据此跳过本次调度；
 *       params 中额外写入 scheduleType、catchUp、catchUpApprovalRequired 等元数据，
 *       供 Orchestrator 记录审计和决策 catch-up 流程。
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class DefaultLaunchAdapterService implements LaunchAdapterService {

  private final CalendarBizDateResolver calendarBizDateResolver;

  @Override
  public LaunchRequest fromApiRequest(TriggerLaunchCommand command) {
    var request = command.request();
    return new LaunchRequest(
        request.getTenantId(),
        request.getJobCode(),
        request.getBizDate(),
        resolveTriggerType(command),
        command.requestId(),
        command.traceId(),
        request.getParams());
  }

  @Override
  public LaunchRequest fromScheduledTrigger(
      ScheduledTriggerCommand command, CalendarBizDateDefinition calendar) {
    var descriptor = command.descriptor();
    // timezone 未配置时 fallback 到 JVM 默认时区，而非抛异常——错误配置不应阻断所有调度触发，
    // 但会导致 bizDate 偏移，运维应在 Job 定义上明确填写 timezone。
    ZoneId zoneId =
        StringUtils.hasText(descriptor.getTimezone())
            ? ZoneId.of(descriptor.getTimezone())
            : ZoneId.systemDefault();
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
    return new LaunchRequest(
        descriptor.getTenantId(),
        descriptor.getJobCode(),
        bizDate,
        triggerType,
        command.requestId(),
        command.traceId(),
        params);
  }

  @Override
  public TriggerType resolveTriggerType(TriggerLaunchCommand command) {
    // API 触发不要求调用方传 triggerType，null 时默认 API 以简化客户端接入
    return command.request().getTriggerType() == null
        ? TriggerType.API
        : command.request().getTriggerType();
  }
}
