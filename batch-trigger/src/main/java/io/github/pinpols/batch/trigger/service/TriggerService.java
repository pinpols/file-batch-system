package io.github.pinpols.batch.trigger.service;

import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.dto.ScheduledOccurrenceCatchUpResponse;
import io.github.pinpols.batch.trigger.domain.ExternalEventLaunchResponse;
import io.github.pinpols.batch.trigger.domain.ExternalEventLaunchStatus;
import io.github.pinpols.batch.trigger.domain.TriggerLaunchStatus;
import io.github.pinpols.batch.trigger.domain.command.ExternalEventLaunchCommand;
import io.github.pinpols.batch.trigger.domain.command.PendingCatchUpApprovalCommand;
import io.github.pinpols.batch.trigger.domain.command.ScheduledOccurrenceCatchUpCommand;
import io.github.pinpols.batch.trigger.domain.command.ScheduledTriggerCommand;
import io.github.pinpols.batch.trigger.domain.command.TriggerLaunchCommand;

/**
 * 触发器核心服务接口，定义任务发射的完整契约。 实现类须保证幂等性（基于幂等键）；{@code launch} 用于 API 手动触发， {@code launchScheduled} /
 * {@code createPendingCatchUp} 由调度引擎内部调用， {@code approvePendingCatchUp} 由运营人工审批后调用。
 */
public interface TriggerService {

  LaunchResponse launch(TriggerLaunchCommand command);

  /** 按原键和完整业务载荷核验补跑回执，不接纳新请求。 */
  LaunchResponse verifyCatchUpSubmission(TriggerLaunchCommand command);

  ExternalEventLaunchResponse launchExternalEvent(ExternalEventLaunchCommand command);

  ExternalEventLaunchStatus findExternalEventStatus(
      String tenantId, String eventSource, String idempotencyKey);

  TriggerLaunchStatus findLaunchStatus(String tenantId, String idempotencyKey);

  TriggerLaunchStatus findLaunchStatus(
      String tenantId, String idempotencyKey, String externalEventSource);

  LaunchResponse launchScheduled(ScheduledTriggerCommand command);

  LaunchResponse createPendingCatchUp(ScheduledTriggerCommand command);

  LaunchResponse approvePendingCatchUp(PendingCatchUpApprovalCommand command);

  ScheduledOccurrenceCatchUpResponse catchUpRejectedOccurrence(
      ScheduledOccurrenceCatchUpCommand command);
}
