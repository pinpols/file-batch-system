package com.example.batch.trigger.service;

import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.trigger.domain.command.PendingCatchUpApprovalCommand;
import com.example.batch.trigger.domain.command.ScheduledTriggerCommand;
import com.example.batch.trigger.domain.command.TriggerLaunchCommand;

public interface TriggerService {

  LaunchResponse launch(TriggerLaunchCommand command);

  LaunchResponse launchScheduled(ScheduledTriggerCommand command);

  LaunchResponse createPendingCatchUp(ScheduledTriggerCommand command);

  LaunchResponse approvePendingCatchUp(PendingCatchUpApprovalCommand command);
}
