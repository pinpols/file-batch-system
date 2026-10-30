package io.github.pinpols.batch.worker.processes.stage;

import io.github.pinpols.batch.common.plugin.IdempotencyCapability;
import io.github.pinpols.batch.common.service.DryRunGuard;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues;
import io.github.pinpols.batch.worker.core.support.TaskExecutionClient;
import io.github.pinpols.batch.worker.processes.domain.ProcessJobContext;
import io.github.pinpols.batch.worker.processes.domain.ProcessStage;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 可能写业务状态的 PROCESS 插件阶段开始前，先持久化重放围栏。 */
@Slf4j
@Component
public class ProcessPluginExecutionFence {

  private final TaskExecutionClient taskExecutionClient;
  private final boolean enabled;

  @Autowired
  public ProcessPluginExecutionFence(TaskExecutionClient taskExecutionClient) {
    this(taskExecutionClient, true);
  }

  private ProcessPluginExecutionFence(TaskExecutionClient taskExecutionClient, boolean enabled) {
    this.taskExecutionClient = taskExecutionClient;
    this.enabled = enabled;
  }

  static ProcessPluginExecutionFence disabledForUnitTests() {
    return new ProcessPluginExecutionFence(null, false);
  }

  /**
   * 返回 true 表示阶段可以继续。远程 Orchestrator 调用发生在
   * PROCESS 调用围栏开启平台事务之前，因此标记独立提交，
   * 早于插件产生业务副作用。
   */
  public boolean authorize(ProcessJobContext context, ProcessStage stage) {
    boolean executionFenceNotRequired = !enabled || !isPotentialWriteStage(stage);
    if (executionFenceNotRequired) {
      return true;
    }
    if (DryRunGuard.fromAttributes(context.getAttributes()).isDryRun()) {
      return true;
    }
    ProcessComputePlugin plugin = context.getResolvedPlugin();
    boolean pluginDoesNotRequireFence =
        EmptyChecks.isNull(plugin) || isReplaySafe(plugin.idempotencyCapability());
    if (pluginDoesNotRequireFence) {
      return true;
    }

    Map<String, Object> attributes = context.getAttributes();
    if (Boolean.TRUE.equals(
        attributes.get(ProcessRuntimeKeys.PROCESS_NON_IDEMPOTENT_FENCE_CONFIRMED))) {
      return true;
    }

    Object rawTaskId = attributes.get(PipelineRuntimeKeys.TASK_ID);
    Long taskId = parseTaskId(rawTaskId);
    Object invocationValue = attributes.get(PipelineRuntimeKeys.PARTITION_INVOCATION_ID);
    String invocationId = invocationValue instanceof String value ? value : null;
    boolean hasPersistedExecutionIdentity = EmptyChecks.isNotNull(taskId)
        && Texts.hasText(context.getTenantId())
        && Texts.hasText(context.getWorkerId())
        && Texts.hasText(invocationId);
    boolean executionFenceCannotBeConfirmed =
        !hasPersistedExecutionIdentity || EmptyChecks.isNull(taskExecutionClient);
    if (executionFenceCannotBeConfirmed) {
      return false;
    }

    try {
      boolean confirmed = taskExecutionClient.markNonIdempotentExecutionStarted(
          context.getTenantId(), taskId, context.getWorkerId(), invocationId);
      if (confirmed) {
        attributes.put(ProcessRuntimeKeys.PROCESS_NON_IDEMPOTENT_FENCE_CONFIRMED, true);
      }
      return confirmed;
    } catch (RuntimeException exception) {
      log.warn(
          "PROCESS replay fence could not be confirmed; plugin stage will not run: tenantId={},"
              + " taskId={}, stage={}, reason={}",
          context.getTenantId(),
          taskId,
          stage,
          exception.getClass().getSimpleName());
      return false;
    }
  }

  private static Long parseTaskId(Object value) {
    if (EmptyChecks.isNull(value)) {
      return null;
    }
    try {
      return PlatformRuntimeValues.toLong(value);
    } catch (NumberFormatException exception) {
      return null;
    }
  }

  private static boolean isPotentialWriteStage(ProcessStage stage) {
    return stage == ProcessStage.COMPUTE
        || stage == ProcessStage.COMMIT
        || stage == ProcessStage.FEEDBACK;
  }

  private static boolean isReplaySafe(IdempotencyCapability capability) {
    return capability == IdempotencyCapability.IDEMPOTENT_BY_UNIQUE_CONSTRAINT
        || capability == IdempotencyCapability.IDEMPOTENT_BY_PLUGIN_LOGIC;
  }
}
