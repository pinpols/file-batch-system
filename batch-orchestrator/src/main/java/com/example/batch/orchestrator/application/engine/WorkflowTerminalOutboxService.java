package com.example.batch.orchestrator.application.engine;

import com.example.batch.common.enums.WorkflowRunStatus;
import com.example.batch.common.event.DomainEvent;
import com.example.batch.common.event.DomainEventPublisher;
import com.example.batch.common.persistence.entity.WorkflowRunEntity;
import com.example.batch.orchestrator.infrastructure.lineage.OpenLineageEmitter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * workflow_run 终态事件 outbox 写入。
 *
 * <p>在 SUCCESS / FAILED / TERMINATED 状态切换的同事务内写入数据库一条 {@code outbox_event}（{@code
 * aggregate_type=WORKFLOW_RUN}，{@code event_type=WORKFLOW_TERMINAL}），由 OutboxForwarder 异步投递到 Kafka
 * topic {@code batch.workflow.terminal.v1}，给 SLA / 监控 / webhook 消费者一份事务一致的工作流终态信号。
 *
 * <p>幂等键 {@code tenantId:workflow:{id}:terminal}：同一 workflow_run 重复进入终态（理论上由前态守护拦掉）也只会落一条 NEW， 重复
 * insert 由 outbox_event 的唯一约束回退（见 V61 迁移）。
 */
@Service
@RequiredArgsConstructor
public class WorkflowTerminalOutboxService {

  private final DomainEventPublisher domainEventPublisher;
  private final OpenLineageEmitter openLineageEmitter;

  /** workflow_run 终态枚举集合（白名单），调用方先行判断后再触发本写入。 */
  public static boolean isTerminal(String runStatus) {
    return WorkflowRunStatus.SUCCESS.code().equals(runStatus)
        || WorkflowRunStatus.FAILED.code().equals(runStatus)
        || WorkflowRunStatus.TERMINATED.code().equals(runStatus)
        || WorkflowRunStatus.SUCCESS_DRY_RUN.code().equals(runStatus)
        || WorkflowRunStatus.FAILED_DRY_RUN.code().equals(runStatus);
  }

  // D-2: MANDATORY 强制 outbox 写入必须在调用方事务内执行，无事务时直接抛异常。
  @Transactional(propagation = Propagation.MANDATORY)
  public void writeTerminalEvent(
      WorkflowRunEntity workflowRun, String terminalStatus, Instant finishedAt) {
    if (workflowRun == null || !isTerminal(terminalStatus)) {
      return;
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("schemaVersion", "v1");
    payload.put("tenantId", workflowRun.getTenantId());
    payload.put("workflowRunId", workflowRun.getId());
    payload.put("workflowDefinitionId", workflowRun.getWorkflowDefinitionId());
    payload.put("relatedJobInstanceId", workflowRun.getRelatedJobInstanceId());
    payload.put("runStatus", terminalStatus);
    payload.put("finishedAt", finishedAt == null ? null : finishedAt.toString());
    payload.put("traceId", workflowRun.getTraceId());

    domainEventPublisher.publish(
        DomainEvent.builder(workflowRun.getTenantId())
            .aggregate("WORKFLOW_RUN", workflowRun.getId())
            .type("WORKFLOW_TERMINAL")
            .key(workflowRun.getTenantId() + ":workflow:" + workflowRun.getId() + ":terminal")
            .payload(payload)
            .traceId(workflowRun.getTraceId())
            .build());

    // P1 OpenLineage:终态真提交后 fire-and-forget emit 血缘 RunEvent。
    // 注册 afterCommit 同步,避免事务回滚时发出假血缘;emitter 内部 disabled 即 no-op,不阻塞主链。
    emitLineageAfterCommit(workflowRun, terminalStatus, finishedAt);
  }

  private void emitLineageAfterCommit(
      WorkflowRunEntity workflowRun, String terminalStatus, Instant finishedAt) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      openLineageEmitter.emitWorkflowTerminal(workflowRun, terminalStatus, finishedAt);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            openLineageEmitter.emitWorkflowTerminal(workflowRun, terminalStatus, finishedAt);
          }
        });
  }
}
