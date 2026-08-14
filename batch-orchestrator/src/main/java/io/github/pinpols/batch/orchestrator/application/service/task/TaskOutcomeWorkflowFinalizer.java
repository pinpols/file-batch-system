package io.github.pinpols.batch.orchestrator.application.service.task;

import io.github.pinpols.batch.common.enums.WorkflowRunStatus;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.orchestrator.application.engine.WorkflowTerminalOutboxService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.OrchestratorWorkflowMappers;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateWorkflowRunStatusParam;
import io.github.pinpols.batch.orchestrator.domain.statemachine.StateMachine;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 负责 worker outcome 驱动的 workflow_run 状态收口。
 *
 * <p>job_instance 的状态已经由主服务完成聚合后，workflow_run 还需要依据 DAG 活跃节点、失败分区和
 * dry-run 语义计算状态，并用 CREATED/RUNNING 前态白名单防止覆盖运维取消或终止。数据库更新成功后，
 * 只有真正进入终态才写 terminal outbox；这些动作必须保持在原 report 事务中，避免 workflow 游标与任务
 * 状态出现跨事务漂移。
 */
@Component
@Slf4j
final class TaskOutcomeWorkflowFinalizer {

  private static final List<String> LIVE_STATUSES =
      List.of(WorkflowRunStatus.CREATED.code(), WorkflowRunStatus.RUNNING.code());

  private final OrchestratorWorkflowMappers workflowMappers;
  private final StateMachine<Object> stateMachine;
  private final WorkflowTerminalOutboxService terminalOutboxService;

  TaskOutcomeWorkflowFinalizer(
      OrchestratorWorkflowMappers workflowMappers,
      StateMachine<Object> stateMachine,
      WorkflowTerminalOutboxService terminalOutboxService) {
    this.workflowMappers = workflowMappers;
    this.stateMachine = stateMachine;
    this.terminalOutboxService = terminalOutboxService;
  }

  void finalizeWorkflow(Context context) {
    String workflowEvent = TaskOutcomeStatePolicy.resolveWorkflowEvent(
        context.failedPartitionCount(),
        context.allPartitionsFinished(),
        context.dagContinues(),
        Boolean.TRUE.equals(context.workflowRun().getDryRun()));
    String workflowStatus =
        stateMachine.transition(context.workflowRun(), workflowEvent).toState();
    Instant workflowFinishedAt = context.jobFullyComplete() ? context.finishedAt() : null;
    int updated =
        workflowMappers.workflowRunMapper.updateStatus(UpdateWorkflowRunStatusParam.builder()
            .tenantId(context.command().tenantId())
            .id(context.workflowRun().getId())
            .runStatus(workflowStatus)
            .currentNodeCode(TaskOutcomeStatePolicy.resolveWorkflowCurrentNode(
                context.activeNodes(), workflowStatus, context.currentNodeCode()))
            .finishedAt(workflowFinishedAt)
            .expectedStatuses(LIVE_STATUSES)
            .build());
    if (updated <= 0) {
      log.warn(
          "workflow_run {} already in terminal state when outcome arrived; skip transition to"
              + " {} (likely cancel/terminate raced ahead)",
          context.workflowRun().getId(),
          workflowStatus);
      return;
    }
    if (WorkflowTerminalOutboxService.isTerminal(workflowStatus)) {
      terminalOutboxService.writeTerminalEvent(
          context.workflowRun(), workflowStatus, workflowFinishedAt);
    }
  }

  @Builder
  record Context(
      TaskOutcomeCommand command,
      WorkflowRunEntity workflowRun,
      long failedPartitionCount,
      boolean allPartitionsFinished,
      boolean dagContinues,
      boolean jobFullyComplete,
      Set<String> activeNodes,
      String currentNodeCode,
      Instant finishedAt) {}
}
