package io.github.pinpols.batch.orchestrator.application.engine.command;

import io.github.pinpols.batch.common.enums.RunMode;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import java.util.Objects;
import lombok.Builder;

/** 同一任务的派发上下文；分区、追踪与运行模式允许沿用原入口的缺省值。 */
@Builder
public record TaskDispatchEventCommand(
    JobInstanceEntity jobInstance,
    JobTaskEntity task,
    JobPartitionEntity partition,
    String traceId,
    String eventKey,
    RunMode runModeOverride) {

  public TaskDispatchEventCommand {
    Objects.requireNonNull(jobInstance, "jobInstance");
    Objects.requireNonNull(task, "task");
  }

  @Override
  public String toString() {
    // task包含执行载荷，不向诊断字符串展开实体。
    return "TaskDispatchEventCommand[redacted]";
  }
}
