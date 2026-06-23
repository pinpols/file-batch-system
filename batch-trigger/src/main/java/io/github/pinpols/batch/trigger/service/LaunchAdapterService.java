package io.github.pinpols.batch.trigger.service;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.trigger.domain.command.ScheduledTriggerCommand;
import io.github.pinpols.batch.trigger.domain.command.TriggerLaunchCommand;
import io.github.pinpols.batch.trigger.support.CalendarBizDateDefinition;

/**
 * 发射请求适配服务接口，负责将不同来源的触发命令（API 请求或调度触发）统一转换为 Orchestrator 可识别的 {@link
 * io.github.pinpols.batch.common.dto.LaunchRequest}。 实现类须根据 {@link
 * io.github.pinpols.batch.common.enums.TriggerType} 正确填充触发类型字段， 日历业务日期信息由调用方通过 {@code
 * CalendarBizDateDefinition} 传入。
 */
public interface LaunchAdapterService {

  LaunchRequest fromApiRequest(TriggerLaunchCommand command);

  LaunchRequest fromScheduledTrigger(
      ScheduledTriggerCommand command, CalendarBizDateDefinition calendar);

  TriggerType resolveTriggerType(TriggerLaunchCommand command);
}
