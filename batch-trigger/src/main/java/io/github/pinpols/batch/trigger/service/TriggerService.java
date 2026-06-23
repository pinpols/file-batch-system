package io.github.pinpols.batch.trigger.service;

import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.trigger.domain.command.PendingCatchUpApprovalCommand;
import io.github.pinpols.batch.trigger.domain.command.ScheduledTriggerCommand;
import io.github.pinpols.batch.trigger.domain.command.TriggerLaunchCommand;

/**
 * 触发器核心服务接口，定义任务发射的完整契约。 实现类须保证幂等性（基于幂等键）；{@code launch} 用于 API 手动触发， {@code launchScheduled} /
 * {@code createPendingCatchUp} 由调度引擎内部调用， {@code approvePendingCatchUp} 由运营人工审批后调用。
 */
public interface TriggerService {

  LaunchResponse launch(TriggerLaunchCommand command);

  LaunchResponse launchScheduled(ScheduledTriggerCommand command);

  LaunchResponse createPendingCatchUp(ScheduledTriggerCommand command);

  LaunchResponse approvePendingCatchUp(PendingCatchUpApprovalCommand command);
}
