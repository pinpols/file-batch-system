package com.example.batch.trigger.service;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.trigger.domain.command.ScheduledTriggerCommand;
import com.example.batch.trigger.domain.command.TriggerLaunchCommand;
import com.example.batch.trigger.support.CalendarBizDateDefinition;

public interface LaunchAdapterService {

    LaunchRequest fromApiRequest(TriggerLaunchCommand command);

    LaunchRequest fromScheduledTrigger(ScheduledTriggerCommand command, CalendarBizDateDefinition calendar);

    TriggerType resolveTriggerType(TriggerLaunchCommand command);
}
