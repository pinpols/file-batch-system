package io.github.pinpols.batch.trigger.web;

import io.github.pinpols.batch.trigger.domain.command.ExternalEventLaunchInput;
import io.github.pinpols.batch.trigger.domain.command.ScheduledOccurrenceCatchUpInput;
import io.github.pinpols.batch.trigger.domain.command.TriggerLaunchInput;
import io.github.pinpols.batch.trigger.web.request.ExternalEventLaunchRequest;
import io.github.pinpols.batch.trigger.web.request.ScheduledOccurrenceCatchUpRequest;
import io.github.pinpols.batch.trigger.web.request.TriggerLaunchRequest;

/** 将 HTTP 请求转换为触发用例输入。 */
public final class TriggerCommandInputMapper {
  private TriggerCommandInputMapper() {}

  public static TriggerLaunchInput toInput(TriggerLaunchRequest request) {
    if (request == null) {
      return null;
    }
    return TriggerLaunchInput.builder()
        .tenantId(request.getTenantId())
        .workflowDefinitionId(request.getWorkflowDefinitionId())
        .bizDate(request.getBizDate())
        .triggerType(request.getTriggerType())
        .params(request.getParams())
        .dataIntervalStart(request.getDataIntervalStart())
        .dataIntervalEnd(request.getDataIntervalEnd())
        .dryRun(request.getDryRun())
        .externalEventCode(request.getExternalEventCode())
        .externalEventSource(request.getExternalEventSource())
        .externalEventId(request.getExternalEventId())
        .build();
  }

  public static ExternalEventLaunchInput toInput(ExternalEventLaunchRequest request) {
    if (request == null) {
      return null;
    }
    return ExternalEventLaunchInput.builder()
        .tenantId(request.getTenantId())
        .externalEventCode(request.getExternalEventCode())
        .externalEventId(request.getExternalEventId())
        .bizDate(request.getBizDate())
        .params(request.getParams())
        .dataIntervalStart(request.getDataIntervalStart())
        .dataIntervalEnd(request.getDataIntervalEnd())
        .dryRun(request.getDryRun())
        .build();
  }

  public static ScheduledOccurrenceCatchUpInput toInput(ScheduledOccurrenceCatchUpRequest request) {
    if (request == null) {
      return null;
    }
    return ScheduledOccurrenceCatchUpInput.builder()
        .tenantId(request.getTenantId())
        .workflowDefinitionId(request.getWorkflowDefinitionId())
        .scheduledFireTime(request.getScheduledFireTime())
        .bizDate(request.getBizDate())
        .requestedBy(request.getRequestedBy())
        .reason(request.getReason())
        .build();
  }
}
