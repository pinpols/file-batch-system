package com.example.batch.trigger.service;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.trigger.domain.command.ScheduledTriggerCommand;
import com.example.batch.trigger.domain.command.TriggerLaunchCommand;
import com.example.batch.trigger.support.CalendarBizDateDefinition;

/**
 * 发射请求适配服务接口，负责将不同来源的触发命令（API 请求或调度触发）统一转换为
 * Orchestrator 可识别的 {@link com.example.batch.common.dto.LaunchRequest}。
 * 实现类须根据 {@link com.example.batch.common.enums.TriggerType} 正确填充触发类型字段，
 * 日历业务日期信息由调用方通过 {@code CalendarBizDateDefinition} 传入。
 */
public interface LaunchAdapterService {

  LaunchRequest fromApiRequest(TriggerLaunchCommand command);

  LaunchRequest fromScheduledTrigger(
      ScheduledTriggerCommand command, CalendarBizDateDefinition calendar);

  TriggerType resolveTriggerType(TriggerLaunchCommand command);
}
