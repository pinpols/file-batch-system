package io.github.pinpols.batch.trigger.service.readiness;

import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.IdGenerator;
import io.github.pinpols.batch.trigger.domain.TriggerDefinitionLoader;
import io.github.pinpols.batch.trigger.domain.command.ScheduledTriggerCommand;
import io.github.pinpols.batch.trigger.service.TriggerService;
import io.github.pinpols.batch.trigger.service.UpstreamNotReadyException;
import io.github.pinpols.batch.trigger.support.TriggerDescriptor;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Orchestrator 驱动的 readiness 恢复启动：由原 fire 身份重建 {@link ScheduledTriggerCommand} 并走正常启动链路。
 *
 * <p>不依赖 Quartz 回调，但保留 Trigger 的全部守卫（就绪闸、版本/日历校验、幂等）。结果以 {@link
 * RecoveryLaunchOutcome} 上报，由调用方（内部端点或后续接管方）决定如何响应。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReadinessRecoveryService {

  private final TriggerDefinitionLoader triggerDefinitionLoader;
  private final TriggerService triggerService;

  /**
   * 按原 fire 身份恢复启动。
   *
   * @return LAUNCHED 已启动；ALREADY_TERMINATED occurrence 已是终态（如被扫尾器超时终结），幂等 skip，
   *     必须如实上报，否则调用方会把等待误标 RELEASED 而该 fire 实际已放弃；NOT_READY 就绪闸未过，可下轮重试；
   *     NOT_FOUND 定义不存在；INVALID_REQUEST 身份不完整；FAILED 其他启动异常。
   */
  public RecoveryLaunchOutcome recover(
      String tenantId, Long workflowDefinitionId, Instant scheduledFireTime) {
    if (EmptyChecks.isBlank(tenantId)
        || EmptyChecks.isNull(workflowDefinitionId)
        || workflowDefinitionId <= 0
        || EmptyChecks.isNull(scheduledFireTime)) {
      return RecoveryLaunchOutcome.INVALID_REQUEST;
    }
    TriggerDescriptor descriptor =
        triggerDefinitionLoader.loadByWorkflowDefinitionId(tenantId, workflowDefinitionId);
    if (EmptyChecks.isNull(descriptor)) {
      return RecoveryLaunchOutcome.NOT_FOUND;
    }
    try {
      LaunchResponse launchResponse = triggerService.launchScheduled(new ScheduledTriggerCommand(
          descriptor,
          scheduledFireTime,
          TriggerType.SCHEDULED,
          IdGenerator.newBusinessNo("readiness"),
          IdGenerator.newTraceId()));
      if (EmptyChecks.isNull(launchResponse) || EmptyChecks.isNull(launchResponse.instanceNo())) {
        return RecoveryLaunchOutcome.ALREADY_TERMINATED;
      }
      return RecoveryLaunchOutcome.LAUNCHED;
    } catch (UpstreamNotReadyException upstreamNotReady) {
      return RecoveryLaunchOutcome.NOT_READY;
    } catch (RuntimeException ex) {
      log.warn(
          "readiness recovery launch failed: tenantId={}, workflowDefinitionId={},"
              + " scheduledFireTime={}",
          tenantId,
          workflowDefinitionId,
          scheduledFireTime,
          ex);
      return RecoveryLaunchOutcome.FAILED;
    }
  }

  /** 恢复启动结果。 */
  public enum RecoveryLaunchOutcome {
    LAUNCHED,
    ALREADY_TERMINATED,
    NOT_READY,
    NOT_FOUND,
    INVALID_REQUEST,
    FAILED
  }
}
