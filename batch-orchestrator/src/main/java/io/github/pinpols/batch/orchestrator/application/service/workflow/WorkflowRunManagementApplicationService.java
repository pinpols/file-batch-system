package io.github.pinpols.batch.orchestrator.application.service.workflow;

import io.github.pinpols.batch.common.enums.AlertSeverity;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.WorkflowNodeCode;
import io.github.pinpols.batch.common.enums.WorkflowNodeRunStatus;
import io.github.pinpols.batch.common.enums.WorkflowRunStatus;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.logging.AuditLogConstants;
import io.github.pinpols.batch.common.logging.LogSanitizer;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.application.engine.WorkflowTerminalOutboxService;
import io.github.pinpols.batch.orchestrator.application.service.governance.AlertEventService;
import io.github.pinpols.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import io.github.pinpols.batch.orchestrator.application.service.task.WorkflowRunResumeProgressor;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowManagementResults.NodeAction;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowManagementResults.RunAction;
import io.github.pinpols.batch.orchestrator.controller.request.AlertEmitRequest;
import io.github.pinpols.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateNodeRunStatusParam;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateWorkflowRunStatusParam;
import io.github.pinpols.batch.orchestrator.mapper.JobExecutionLogMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 工作流运行实例的生命周期管理应用服务，提供取消、终止和跳过节点等运维操作。
 *
 * <p>取消和终止最终均将运行状态置为 {@code TERMINATED}，区别在于允许的前置状态范围不同： 取消允许从 {@code CREATED} 或 {@code RUNNING}
 * 发起，而终止仅允许 {@code RUNNING} 状态。 节点跳过（{@link #skipNode}）仅作用于 {@code FAILED} 的节点运行记录， 可在工作流整体处于
 * {@code RUNNING} 或 {@code FAILED} 时使用，以便跳过卡点后续继续推进。
 *
 * <p>所有 workflow_run 状态变更经 SQL 期望前态守护（避免与 task outcome 抢占造成 TERMINATED 被覆写），并在 SUCCESS / FAILED /
 * TERMINATED 终态切换的同事务内向 outbox 写一条 {@code WORKFLOW_TERMINAL} 事件，给 SLA / 监控 / webhook 留事务一致信号。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WorkflowRunManagementApplicationService {

  private static final Set<String> CANCELLABLE =
      Set.of(WorkflowRunStatus.CREATED.code(), WorkflowRunStatus.RUNNING.code());
  private static final Set<String> TERMINABLE = Set.of(WorkflowRunStatus.RUNNING.code());
  // ADR-044:仅 RUNNING 可暂停 DAG 推进;PAUSED 可恢复回 RUNNING。
  private static final Set<String> PAUSABLE = Set.of(WorkflowRunStatus.RUNNING.code());
  private static final Set<String> RESUMABLE = Set.of(WorkflowRunStatus.PAUSED.code());

  private final WorkflowRunMapper workflowRunMapper;
  private final WorkflowNodeRunMapper workflowNodeRunMapper;
  private final WorkflowTerminalOutboxService workflowTerminalOutboxService;
  private final WorkflowDagService workflowDagService;
  private final ObjectProvider<WorkflowNodeDispatchService> workflowNodeDispatchServiceProvider;
  private final ObjectProvider<WorkflowRunResumeProgressor> resumeProgressorProvider;
  private final OrchestratorJobMappers jobMappers;
  // P1-2: audit + alert 依赖。新增可选 bean,生产环境必然装配;单测构造可传 null。
  private final ObjectProvider<JobExecutionLogMapper> jobExecutionLogMapperProvider;
  private final ObjectProvider<AlertEventService> alertEventServiceProvider;
  private final WorkflowNodeTerminalFinalizer workflowNodeTerminalFinalizer;

  @Transactional
  public RunAction cancel(String tenantId, Long id) {
    WorkflowRunEntity run = findRun(tenantId, id);
    if (!CANCELLABLE.contains(run.getRunStatus())) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.workflow.cancel_invalid_state", run.getRunStatus());
    }
    return flipToTerminated(run, CANCELLABLE);
  }

  @Transactional
  public RunAction terminate(String tenantId, Long id) {
    WorkflowRunEntity run = findRun(tenantId, id);
    if (!TERMINABLE.contains(run.getRunStatus())) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          ResultCode.STATE_CONFLICT.detailKey(),
          "cannot terminate from " + run.getRunStatus());
    }
    return flipToTerminated(run, TERMINABLE);
  }

  /** ADR-044 暂停 RUNNING → PAUSED:停止推进下游 DAG 节点,在途节点自然终结。 */
  @Transactional
  public RunAction pause(String tenantId, Long id) {
    return lifecycleFlip(tenantId, id, PAUSABLE, WorkflowRunStatus.PAUSED.code());
  }

  /** ADR-044 恢复 PAUSED → RUNNING:重新推进 DAG。 */
  @Transactional
  public RunAction resume(String tenantId, Long id) {
    RunAction action = lifecycleFlip(tenantId, id, RESUMABLE, WorkflowRunStatus.RUNNING.code());
    Runnable progress = () -> resumeProgressorProvider.getObject().resume(tenantId, id);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      // 子 Job 启动会在 REQUIRES_NEW 事务中把其 Job Instance 关联到 workflow_node_run。
      // 等此状态转换事务提交后再重放 DAG，避免子事务
      // 等待当前请求事务仍持有的锁。
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          progress.run();
        }
      });
    } else {
      progress.run();
    }
    return action;
  }

  /**
   * 非终态生命周期翻转(pause/resume 专用)。
   *
   * <p>复用 expectedStatuses 守护做 CAS;不动 finished_at、不发 outbox(非终态)。
   */
  private RunAction lifecycleFlip(
      String tenantId, Long id, Set<String> expectedFrom, String targetStatus) {
    WorkflowRunEntity run = findRun(tenantId, id);
    if (!expectedFrom.contains(run.getRunStatus())) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          ResultCode.STATE_CONFLICT.detailKey(),
          "cannot transition from " + run.getRunStatus() + " to " + targetStatus);
    }
    int updated = workflowRunMapper.updateStatus(UpdateWorkflowRunStatusParam.builder()
        .tenantId(tenantId)
        .id(id)
        .runStatus(targetStatus)
        .currentNodeCode(run.getCurrentNodeCode())
        .expectedStatuses(expectedFrom)
        .build());
    if (updated <= 0) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.common.concurrent_modification");
    }
    return new RunAction(id, targetStatus);
  }

  /** 老入口,backward-compat;新代码请传 operatorId / reason。 */
  @Transactional
  public NodeAction skipNode(String tenantId, Long id, String nodeCode) {
    return skipNodeInternal(tenantId, id, nodeCode, null, null);
  }

  @Transactional
  public NodeAction skipNode(
      String tenantId, Long id, String nodeCode, String operatorId, String reason) {
    return skipNodeInternal(tenantId, id, nodeCode, operatorId, reason);
  }

  private NodeAction skipNodeInternal(
      String tenantId, Long id, String nodeCode, String operatorId, String reason) {
    WorkflowRunEntity run = findRun(tenantId, id);
    if (!WorkflowRunStatus.RUNNING.code().equals(run.getRunStatus())
        && !WorkflowRunStatus.FAILED.code().equals(run.getRunStatus())) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.skip_node_state_invalid");
    }
    WorkflowNodeRunEntity nodeRun = Guard.requireFound(
        workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(id, nodeCode),
        "node run not found: " + nodeCode);
    if (!WorkflowNodeRunStatus.FAILED.code().equals(nodeRun.getNodeStatus())) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          ResultCode.STATE_CONFLICT.detailKey(),
          "can only skip FAILED nodes, current: " + nodeRun.getNodeStatus());
    }
    int updated = workflowNodeRunMapper.updateStatus(UpdateNodeRunStatusParam.builder()
        .id(nodeRun.getId())
        .nodeStatus(WorkflowNodeRunStatus.SKIPPED.code())
        .expectedStatus(WorkflowNodeRunStatus.FAILED.code())
        .errorCode(null)
        .errorMessage(null)
        .skipReasonCode("OPERATOR_SKIPPED")
        .skipReason(reason)
        .durationMs(nodeRun.getDurationMs())
        .finishedAt(BatchDateTimeSupport.utcNow())
        .build());
    if (updated <= 0) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.common.concurrent_modification");
    }
    advanceDownstreamAfterSkip(run, nodeCode);
    // P1-2: 写 audit 行 + 发 WARN alert,补齐"运维介入"事后追溯。
    Long nodeRunId = nodeRun.getId();
    Long jobInstanceId = nodeRun.getJobInstanceId();
    appendSkipNodeAudit(
        run, nodeCode, nodeRunId, jobInstanceId, operatorId, reason);
    emitSkipNodeAlert(run, nodeRun, operatorId);
    return new NodeAction(id, nodeCode, WorkflowNodeRunStatus.SKIPPED.code());
  }

  private void appendSkipNodeAudit(
      WorkflowRunEntity run,
      String nodeCode,
      Long nodeRunId,
      Long jobInstanceId,
      String operatorId,
      String reason) {
    JobExecutionLogMapper mapper = jobExecutionLogMapperProvider == null
        ? null
        : jobExecutionLogMapperProvider.getIfAvailable();
    if (mapper == null) {
      return;
    }
    JobExecutionLogEntity audit = new JobExecutionLogEntity();
    audit.setTenantId(run.getTenantId());
    audit.setJobInstanceId(jobInstanceId);
    audit.setLogLevel("INFO");
    audit.setLogType(AuditLogConstants.LOG_TYPE_AUDIT);
    audit.setTraceId(run.getTraceId());
    audit.setMessage(AuditLogConstants.AUDIT_OP_WORKFLOW_NODE_SKIP);
    audit.setDetailRef(AuditLogConstants.DETAIL_REF_WORKFLOW_NODE_RUN);
    Map<String, Object> extra = new LinkedHashMap<>();
    extra.put("workflowRunId", run.getId());
    extra.put("workflowDefinitionId", run.getWorkflowDefinitionId());
    extra.put("nodeCode", nodeCode);
    extra.put("nodeRunId", nodeRunId);
    extra.put("bizDate", EmptyChecks.isNull(run.getBizDate()) ? null : run.getBizDate().toString());
    extra.put(
        "operatorId",
        Texts.hasText(operatorId) ? operatorId : AuditLogConstants.OPERATOR_ID_SYSTEM);
    extra.put(
        "operatorType",
        Texts.hasText(operatorId)
            ? AuditLogConstants.OPERATOR_TYPE_REQUEST
            : AuditLogConstants.OPERATOR_TYPE_SYSTEM);
    extra.put("reason", reason);
    String extraJson = JsonUtils.toJson(extra);
    audit.setExtraJson(extraJson);
    mapper.insert(audit);
  }

  private void emitSkipNodeAlert(
      WorkflowRunEntity run, WorkflowNodeRunEntity nodeRun, String operatorId) {
    AlertEventService alertService =
        alertEventServiceProvider == null ? null : alertEventServiceProvider.getIfAvailable();
    if (alertService == null) {
      return;
    }
    String resourceKey = "workflow_run:" + run.getId() + ":node:" + nodeRun.getNodeCode();
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("workflowRunId", run.getId());
    detail.put("workflowDefinitionId", run.getWorkflowDefinitionId());
    detail.put("nodeCode", nodeRun.getNodeCode());
    detail.put("nodeRunId", nodeRun.getId());
    detail.put(
        "jobInstanceId",
        EmptyChecks.isNull(nodeRun.getJobInstanceId()) ? null : nodeRun.getJobInstanceId().toString());
    detail.put("bizDate", EmptyChecks.isNull(run.getBizDate()) ? null : run.getBizDate().toString());
    detail.put("operatorId", operatorId);
    AlertEmitRequest alertRequest = AlertEmitRequest.builder()
        .tenantId(run.getTenantId())
        .serviceName("batch-orchestrator")
        .alertType("WORKFLOW_NODE_MANUAL_SKIP")
        .severity(AlertSeverity.WARN.code())
        .title("Workflow node manually skipped")
        .resourceKey(resourceKey)
        .detailJson(JsonUtils.toJson(detail))
        .traceId(run.getTraceId())
        .build();
    alertService.emit(alertRequest);
  }

  /** 把 run 切到 TERMINATED：SQL 期望前态守护 + 同事务发 outbox 终态事件。 */
  private RunAction flipToTerminated(WorkflowRunEntity run, Set<String> expectedFrom) {
    Instant finishedAt = BatchDateTimeSupport.utcNow();
    int updated = workflowRunMapper.updateStatus(UpdateWorkflowRunStatusParam.builder()
        .tenantId(run.getTenantId())
        .id(run.getId())
        .runStatus(WorkflowRunStatus.TERMINATED.code())
        .currentNodeCode(run.getCurrentNodeCode())
        .finishedAt(finishedAt)
        .expectedStatuses(expectedFrom)
        .build());
    if (updated <= 0) {
      // SELECT-then-UPDATE race：findRun 看到合法前态后 outcome 把 run 推到了终态
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          "error.workflow.cancel_invalid_state",
          "<concurrent transition>");
    }
    workflowTerminalOutboxService.writeTerminalEvent(
        run, WorkflowRunStatus.TERMINATED.code(), finishedAt);
    return new RunAction(run.getId(), WorkflowRunStatus.TERMINATED.code());
  }

  /** 人工 skip 后沿可满足边推进，并持久化不会触发的分支。 */
  private void advanceDownstreamAfterSkip(WorkflowRunEntity run, String nodeCode) {
    if (EmptyChecks.isNull(run.getWorkflowDefinitionId())) {
      log.warn(
          "skipNode advancement skipped: workflow_run {} has no workflowDefinitionId", run.getId());
      return;
    }
    Long relatedJobInstanceId = run.getRelatedJobInstanceId();
    JobInstanceEntity jobInstance = EmptyChecks.isNull(relatedJobInstanceId)
        ? null
        : jobMappers.jobInstanceMapper.selectById(run.getTenantId(), relatedJobInstanceId);
    boolean relatedJobInstanceMissing =
        EmptyChecks.isNotNull(relatedJobInstanceId) && EmptyChecks.isNull(jobInstance);
    if (relatedJobInstanceMissing) {
      log.warn(
          "skipNode advancement skipped: jobInstance {} not found for workflow_run {}",
          run.getRelatedJobInstanceId(),
          run.getId());
      return;
    }
    List<WorkflowDagService.DagNodeResolution> nextNodes =
        workflowDagService.resolveNextNodesForRun(run, nodeCode, true, null);
    WorkflowNodeDispatchService dispatchService = null;
    if (EmptyChecks.isNotNull(nextNodes)) {
      for (WorkflowDagService.DagNodeResolution next : nextNodes) {
        if (EmptyChecks.isNull(next)) {
          continue;
        }
        if (WorkflowNodeCode.END.code().equals(next.nodeCode())) {
          log.debug(
              "manual skipNode {} reached END on workflow_run {}",
              LogSanitizer.value(nodeCode),
              run.getId());
          continue;
        }
        if (!workflowDagService.isNodeReadyForRunDispatch(run, next.nodeCode(), null)) {
          continue;
        }
        if (EmptyChecks.isNull(dispatchService)) {
          dispatchService = workflowNodeDispatchServiceProvider.getObject();
        }
        String traceId =
            EmptyChecks.isNull(jobInstance) ? run.getTraceId() : jobInstance.getTraceId();
        dispatchService.dispatchNode(jobInstance, run, next, null, traceId);
      }
    }
    // 与 TaskOutcomeDagProgressor 保持一致：每个终态结果都会生成后续无法
    // 触发的分支，避免运维人员跳过节点后条件/失败分支不可见。
    workflowDagService.cascadeSkipDownstreamForRun(run, nodeCode, null);
    // 只要仍有派发中的/兄弟节点活动，共用收尾器就不执行；当本次跳过关闭最后一个待处理节点后，
    // 由它负责写入终态并发送 Outbox 事件。
    workflowNodeTerminalFinalizer.finalizeIfAllNodesTerminal(run);
  }

  private WorkflowRunEntity findRun(String tenantId, Long id) {
    WorkflowRunEntity loadedRun = workflowRunMapper.selectById(tenantId, id);
    return Guard.requireFound(loadedRun, "workflow run not found");
  }
}
