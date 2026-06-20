package com.example.batch.orchestrator.application.service.workflow;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.WorkflowNodeCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.logging.AuditLogConstants;
import com.example.batch.common.persistence.entity.WorkflowRunEntity;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.Guard;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.application.engine.WorkflowTerminalOutboxService;
import com.example.batch.orchestrator.application.service.governance.AlertEventService;
import com.example.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import com.example.batch.orchestrator.controller.request.AlertEmitRequest;
import com.example.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import com.example.batch.orchestrator.domain.param.UpdateNodeRunStatusParam;
import com.example.batch.orchestrator.domain.param.UpdateWorkflowRunStatusParam;
import com.example.batch.orchestrator.mapper.JobExecutionLogMapper;
import com.example.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import com.example.batch.orchestrator.mapper.WorkflowRunMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String STATUS_TERMINATED = "TERMINATED";

  private static final Set<String> CANCELLABLE = Set.of("CREATED", "RUNNING");
  private static final Set<String> TERMINABLE = Set.of("RUNNING");
  // ADR-044:仅 RUNNING 可暂停 DAG 推进;PAUSED 可恢复回 RUNNING。
  private static final Set<String> PAUSABLE = Set.of("RUNNING");
  private static final Set<String> RESUMABLE = Set.of("PAUSED");
  private static final String STATUS_PAUSED = "PAUSED";
  private static final String STATUS_RUNNING = "RUNNING";

  private final WorkflowRunMapper workflowRunMapper;
  private final WorkflowNodeRunMapper workflowNodeRunMapper;
  private final WorkflowTerminalOutboxService workflowTerminalOutboxService;
  private final WorkflowDagService workflowDagService;
  private final ObjectProvider<WorkflowNodeDispatchService> workflowNodeDispatchServiceProvider;
  private final OrchestratorJobMappers jobMappers;
  // P1-2: audit + alert 依赖。新增可选 bean,生产环境必然装配;单测构造可传 null。
  private final ObjectProvider<JobExecutionLogMapper> jobExecutionLogMapperProvider;
  private final ObjectProvider<AlertEventService> alertEventServiceProvider;

  @Transactional
  public Map<String, Object> cancel(String tenantId, Long id) {
    WorkflowRunEntity run = findRun(tenantId, id);
    if (!CANCELLABLE.contains(run.getRunStatus())) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.workflow.cancel_invalid_state", run.getRunStatus());
    }
    return flipToTerminated(run, CANCELLABLE);
  }

  @Transactional
  public Map<String, Object> terminate(String tenantId, Long id) {
    WorkflowRunEntity run = findRun(tenantId, id);
    if (!TERMINABLE.contains(run.getRunStatus())) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          "error.common.state_conflict_detail",
          "cannot terminate from " + run.getRunStatus());
    }
    return flipToTerminated(run, TERMINABLE);
  }

  /** ADR-044 暂停 RUNNING → PAUSED:停止推进下游 DAG 节点,在途节点自然终结。 */
  @Transactional
  public Map<String, Object> pause(String tenantId, Long id) {
    return lifecycleFlip(tenantId, id, PAUSABLE, STATUS_PAUSED);
  }

  /** ADR-044 恢复 PAUSED → RUNNING:重新推进 DAG。 */
  @Transactional
  public Map<String, Object> resume(String tenantId, Long id) {
    return lifecycleFlip(tenantId, id, RESUMABLE, STATUS_RUNNING);
  }

  /**
   * 非终态生命周期翻转(pause/resume 专用)。
   *
   * <p>复用 expectedStatuses 守护做 CAS;不动 finished_at、不发 outbox(非终态)。
   */
  private Map<String, Object> lifecycleFlip(
      String tenantId, Long id, Set<String> expectedFrom, String targetStatus) {
    WorkflowRunEntity run = findRun(tenantId, id);
    if (!expectedFrom.contains(run.getRunStatus())) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          "error.common.state_conflict_detail",
          "cannot transition from " + run.getRunStatus() + " to " + targetStatus);
    }
    int updated =
        workflowRunMapper.updateStatus(
            UpdateWorkflowRunStatusParam.builder()
                .tenantId(tenantId)
                .id(id)
                .runStatus(targetStatus)
                .currentNodeCode(run.getCurrentNodeCode())
                .expectedStatuses(expectedFrom)
                .build());
    if (updated <= 0) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.common.concurrent_modification");
    }
    return Map.of("id", id, "status", targetStatus);
  }

  /** 老入口,backward-compat;新代码请传 operatorId / reason。 */
  public Map<String, Object> skipNode(String tenantId, Long id, String nodeCode) {
    return skipNode(tenantId, id, nodeCode, null, null);
  }

  @Transactional
  public Map<String, Object> skipNode(
      String tenantId, Long id, String nodeCode, String operatorId, String reason) {
    WorkflowRunEntity run = findRun(tenantId, id);
    if (!"RUNNING".equals(run.getRunStatus()) && !"FAILED".equals(run.getRunStatus())) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.skip_node_state_invalid");
    }
    WorkflowNodeRunEntity nodeRun =
        Guard.requireFound(
            workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(id, nodeCode),
            "node run not found: " + nodeCode);
    if (!"FAILED".equals(nodeRun.getNodeStatus())) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          "error.common.state_conflict_detail",
          "can only skip FAILED nodes, current: " + nodeRun.getNodeStatus());
    }
    workflowNodeRunMapper.updateStatus(
        UpdateNodeRunStatusParam.builder()
            .id(nodeRun.getId())
            .nodeStatus("SKIPPED")
            .errorCode(null)
            .errorMessage(null)
            .durationMs(nodeRun.getDurationMs())
            .finishedAt(BatchDateTimeSupport.utcNow())
            .build());
    advanceDownstreamAfterSkip(run, nodeCode);
    // P1-2: 写 audit 行 + 发 WARN alert,补齐"运维介入"事后追溯。
    appendSkipNodeAudit(run, nodeCode, nodeRun.getId(), operatorId, reason);
    emitSkipNodeAlert(run, nodeCode, operatorId);
    return Map.of("id", id, "nodeCode", nodeCode, "nodeStatus", "SKIPPED");
  }

  private void appendSkipNodeAudit(
      WorkflowRunEntity run, String nodeCode, Long nodeRunId, String operatorId, String reason) {
    JobExecutionLogMapper mapper =
        jobExecutionLogMapperProvider == null
            ? null
            : jobExecutionLogMapperProvider.getIfAvailable();
    if (mapper == null) {
      return;
    }
    JobExecutionLogEntity audit = new JobExecutionLogEntity();
    audit.setTenantId(run.getTenantId());
    audit.setJobInstanceId(run.getRelatedJobInstanceId());
    audit.setLogLevel("INFO");
    audit.setLogType(AuditLogConstants.LOG_TYPE_AUDIT);
    audit.setMessage(AuditLogConstants.AUDIT_OP_WORKFLOW_NODE_SKIP);
    audit.setDetailRef(AuditLogConstants.DETAIL_REF_WORKFLOW_NODE_RUN);
    Map<String, Object> extra = new LinkedHashMap<>();
    extra.put("workflowRunId", run.getId());
    extra.put("nodeCode", nodeCode);
    extra.put("nodeRunId", nodeRunId);
    extra.put(
        "operatorId",
        Texts.hasText(operatorId) ? operatorId : AuditLogConstants.OPERATOR_ID_SYSTEM);
    extra.put(
        "operatorType",
        Texts.hasText(operatorId)
            ? AuditLogConstants.OPERATOR_TYPE_REQUEST
            : AuditLogConstants.OPERATOR_TYPE_SYSTEM);
    extra.put("reason", reason);
    audit.setExtraJson(JsonUtils.toJson(extra));
    mapper.insert(audit);
  }

  private void emitSkipNodeAlert(WorkflowRunEntity run, String nodeCode, String operatorId) {
    AlertEventService alertService =
        alertEventServiceProvider == null ? null : alertEventServiceProvider.getIfAvailable();
    if (alertService == null) {
      return;
    }
    String resourceKey = "workflow_run:" + run.getId() + ":node:" + nodeCode;
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("workflowRunId", run.getId());
    detail.put("nodeCode", nodeCode);
    detail.put("operatorId", operatorId);
    alertService.emit(
        AlertEmitRequest.builder()
            .tenantId(run.getTenantId())
            .serviceName("batch-orchestrator")
            .alertType("WORKFLOW_NODE_MANUAL_SKIP")
            .severity("WARN")
            .title("Workflow node manually skipped")
            .resourceKey(resourceKey)
            .detailJson(JsonUtils.toJson(detail))
            .traceId(null)
            .build());
  }

  /** 把 run 切到 TERMINATED：SQL 期望前态守护 + 同事务发 outbox 终态事件。 */
  private Map<String, Object> flipToTerminated(WorkflowRunEntity run, Set<String> expectedFrom) {
    Instant finishedAt = BatchDateTimeSupport.utcNow();
    int updated =
        workflowRunMapper.updateStatus(
            UpdateWorkflowRunStatusParam.builder()
                .tenantId(run.getTenantId())
                .id(run.getId())
                .runStatus(STATUS_TERMINATED)
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
    workflowTerminalOutboxService.writeTerminalEvent(run, STATUS_TERMINATED, finishedAt);
    return Map.of("id", run.getId(), "status", STATUS_TERMINATED);
  }

  /**
   * 人工 skip 后把节点视作"已成功"继续推进 DAG：沿 SUCCESS / ALWAYS / CONDITION 边解析下游并 dispatch；命中 END 节点时仅记日志，
   * workflow_run 终态由后续节点的 outcome 自然收口（避免在管理路径里复刻 state machine + outbox 的全套切换）。
   */
  private void advanceDownstreamAfterSkip(WorkflowRunEntity run, String nodeCode) {
    if (run.getRelatedJobInstanceId() == null || run.getWorkflowDefinitionId() == null) {
      log.warn(
          "skipNode advancement skipped: workflow_run {} has no relatedJobInstanceId or"
              + " workflowDefinitionId",
          run.getId());
      return;
    }
    JobInstanceEntity jobInstance =
        jobMappers.jobInstanceMapper.selectById(run.getTenantId(), run.getRelatedJobInstanceId());
    if (jobInstance == null) {
      log.warn(
          "skipNode advancement skipped: jobInstance {} not found for workflow_run {}",
          run.getRelatedJobInstanceId(),
          run.getId());
      return;
    }
    var nextNodes =
        workflowDagService.resolveNextNodes(run.getWorkflowDefinitionId(), nodeCode, true, null);
    if (nextNodes == null || nextNodes.isEmpty()) {
      return;
    }
    WorkflowNodeDispatchService dispatchService = workflowNodeDispatchServiceProvider.getObject();
    for (WorkflowDagService.DagNodeResolution next : nextNodes) {
      if (next == null) {
        continue;
      }
      if (WorkflowNodeCode.END.code().equals(next.nodeCode())) {
        log.warn(
            "manual skipNode {} routed to END on workflow_run {}; workflow_run terminal will be"
                + " driven by next outcome",
            nodeCode,
            run.getId());
        continue;
      }
      if (!workflowDagService.isNodeReadyForDispatch(
          run.getId(), run.getWorkflowDefinitionId(), next.nodeCode(), null)) {
        continue;
      }
      dispatchService.dispatchNode(jobInstance, run, next, null, jobInstance.getTraceId());
    }
  }

  private WorkflowRunEntity findRun(String tenantId, Long id) {
    return Guard.requireFound(workflowRunMapper.selectById(tenantId, id), "workflow run not found");
  }
}
