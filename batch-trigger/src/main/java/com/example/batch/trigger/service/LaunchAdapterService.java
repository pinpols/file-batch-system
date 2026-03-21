package com.example.batch.trigger.service;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.trigger.domain.command.ScheduledTriggerCommand;
import com.example.batch.trigger.domain.command.TriggerLaunchCommand;

public interface LaunchAdapterService {

    LaunchRequest fromApiRequest(TriggerLaunchCommand command);

    LaunchRequest fromScheduledTrigger(ScheduledTriggerCommand command);

    TriggerType resolveTriggerType(TriggerLaunchCommand command);
}
