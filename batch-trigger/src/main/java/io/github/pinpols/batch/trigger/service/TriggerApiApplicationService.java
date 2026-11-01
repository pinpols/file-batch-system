package io.github.pinpols.batch.trigger.service;

import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.dto.ScheduledOccurrenceCatchUpResponse;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.IdGenerator;
import io.github.pinpols.batch.trigger.auth.TriggerApiKeyPrincipal;
import io.github.pinpols.batch.trigger.domain.ExternalEventLaunchResponse;
import io.github.pinpols.batch.trigger.domain.ExternalEventLaunchStatus;
import io.github.pinpols.batch.trigger.domain.TriggerDrainService;
import io.github.pinpols.batch.trigger.domain.TriggerLaunchStatus;
import io.github.pinpols.batch.trigger.domain.command.ExternalEventLaunchCommand;
import io.github.pinpols.batch.trigger.domain.command.ExternalEventLaunchInput;
import io.github.pinpols.batch.trigger.domain.command.PendingCatchUpApprovalCommand;
import io.github.pinpols.batch.trigger.domain.command.ScheduledOccurrenceCatchUpCommand;
import io.github.pinpols.batch.trigger.domain.command.ScheduledOccurrenceCatchUpInput;
import io.github.pinpols.batch.trigger.domain.command.TriggerLaunchCommand;
import io.github.pinpols.batch.trigger.domain.command.TriggerLaunchInput;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Trigger 接口的鉴权、排水、并发准入及用例协调。 */
@Service
@RequiredArgsConstructor
public class TriggerApiApplicationService {
  private final TriggerService triggerService;
  private final TriggerDrainService drainService;
  private final TriggerLaunchAdmissionService launchAdmissionService;
  private final TriggerRequestPolicy requestPolicy;

  public LaunchResponse launch(
      TriggerApiKeyPrincipal principal,
      TriggerLaunchInput input,
      String idempotencyKey,
      String requestId,
      String traceId) {
    requestPolicy.authorizeLaunch(
        principal, input.triggerType(), input.tenantId(), input.externalEventSource());
    drainService.ensureAcceptingLaunches();
    String finalRequestId =
        EmptyChecks.isBlank(requestId) ? IdGenerator.newBusinessNo("req") : requestId;
    String finalTraceId = EmptyChecks.isBlank(traceId) ? IdGenerator.newTraceId() : traceId;
    TriggerLaunchCommand command =
        new TriggerLaunchCommand(input, idempotencyKey, finalRequestId, finalTraceId);
    return launchAdmissionService.execute(() -> triggerService.launch(command));
  }

  public ExternalEventLaunchResponse launchEvent(
      TriggerApiKeyPrincipal principal,
      ExternalEventLaunchInput input,
      String idempotencyKey,
      String requestId,
      String traceId) {
    requestPolicy.authorizeExternalEvent(principal, input.tenantId());
    drainService.ensureAcceptingLaunches();
    String finalRequestId =
        EmptyChecks.isBlank(requestId) ? IdGenerator.newBusinessNo("event") : requestId;
    String finalTraceId = EmptyChecks.isBlank(traceId) ? IdGenerator.newTraceId() : traceId;
    ExternalEventLaunchCommand command = new ExternalEventLaunchCommand(
        input, principal.keyName(), idempotencyKey, finalRequestId, finalTraceId);
    return launchAdmissionService.execute(() -> triggerService.launchExternalEvent(command));
  }

  public ScheduledOccurrenceCatchUpResponse catchUpRejectedOccurrence(
      ScheduledOccurrenceCatchUpInput input,
      String idempotencyKey,
      String requestId,
      String traceId) {
    drainService.ensureAcceptingLaunches();
    String finalRequestId =
        EmptyChecks.isBlank(requestId) ? IdGenerator.newBusinessNo("req") : requestId;
    String finalTraceId = EmptyChecks.isBlank(traceId) ? IdGenerator.newTraceId() : traceId;
    ScheduledOccurrenceCatchUpCommand command =
        new ScheduledOccurrenceCatchUpCommand(input, idempotencyKey, finalRequestId, finalTraceId);
    return launchAdmissionService.execute(() -> triggerService.catchUpRejectedOccurrence(command));
  }

  public ExternalEventLaunchStatus externalEventStatus(
      TriggerApiKeyPrincipal principal, String tenantId, String idempotencyKey) {
    requestPolicy.authorizeExternalEvent(principal, tenantId);
    return triggerService.findExternalEventStatus(tenantId, principal.keyName(), idempotencyKey);
  }

  public TriggerLaunchStatus launchStatus(
      TriggerApiKeyPrincipal principal, String tenantId, String idempotencyKey) {
    if (EmptyChecks.isNull(principal)) {
      return triggerService.findLaunchStatus(tenantId, idempotencyKey);
    }
    requestPolicy.authorizeExternalEvent(principal, tenantId);
    return triggerService.findLaunchStatus(tenantId, idempotencyKey, principal.keyName());
  }

  public LaunchResponse approveCatchUp(PendingCatchUpApprovalCommand command) {
    drainService.ensureAcceptingLaunches();
    return triggerService.approvePendingCatchUp(command);
  }
}
