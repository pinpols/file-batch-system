package io.github.pinpols.batch.orchestrator.application.service.task;

import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 负责把 JOB 节点子作业的终态转换为父工作流中的虚拟任务回报。
 *
 * <p>子作业和父工作流共享同一套 task outcome 状态机，但父虚拟任务不能直接推进水位；子作业已经完成
 * 自己的水位写入，父侧只透传终态和产出，让原有的分区/DAG 推进逻辑继续处理后继节点。因此这里仅负责
 * 解析父任务引用和构造回报命令，真正的状态更新仍由 report 主服务在当前事务中执行。
 */
@Component
final class TaskOutcomeParentTaskSignaler {

  Optional<TaskOutcomeCommand> buildParentOutcome(
      JobInstanceEntity childJobInstance,
      String childInstanceStatus,
      TaskOutcomeCommand childCommand) {
    Long parentVirtualTaskId =
        ParentVirtualTaskIdResolver.resolve(childJobInstance.getParamsSnapshot());
    if (EmptyChecks.isNull(parentVirtualTaskId)) {
      return Optional.empty();
    }
    boolean nodeSuccess = JobInstanceStatus.SUCCESS.code().equals(childInstanceStatus);
    // 父虚拟任务不共享子作业的 high_water_mark_out，只透传子作业结果供下游 DSL 使用。
    TaskOutcomeCommand parentCommand = TaskOutcomeCommand.builder()
        .tenantId(childJobInstance.getTenantId())
        .taskId(parentVirtualTaskId)
        .success(nodeSuccess)
        .resultSummary(JsonUtils.toJson(Map.of("childInstanceStatus", childInstanceStatus)))
        .errorCode(nodeSuccess ? null : childCommand.errorCode())
        .errorMessage(nodeSuccess ? null : childCommand.errorMessage())
        .errorKey(nodeSuccess ? null : childCommand.errorKey())
        .errorArgs(nodeSuccess ? null : childCommand.errorArgs())
        .failureClass(nodeSuccess ? null : childCommand.failureClass())
        .outputs(nodeSuccess ? childCommand.outputs() : null)
        .build();
    return Optional.of(parentCommand);
  }
}
