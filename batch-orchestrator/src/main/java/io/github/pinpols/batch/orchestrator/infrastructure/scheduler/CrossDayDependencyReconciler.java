package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.service.governance.AlertEventService;
import io.github.pinpols.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import io.github.pinpols.batch.orchestrator.application.service.workflow.CrossDayDependencyResolver;
import io.github.pinpols.batch.orchestrator.application.service.workflow.CrossDayDependencyResolver.ResolutionResult;
import io.github.pinpols.batch.orchestrator.application.service.workflow.OrchestratorWorkflowMappers;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowDagService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowNodeDispatchService;
import io.github.pinpols.batch.orchestrator.config.CrossDayDependencyReconcileProperties;
import io.github.pinpols.batch.orchestrator.controller.request.AlertEmitRequest;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateNodeRunStatusParam;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ADR-018 §决策 §调度治理 Stages 5 + 7 — 跨批量日依赖 reconciler。
 *
 * <p>每轮扫描 {@code workflow_node_run.node_status='WAITING_DEPENDENCY'} 节点：
 *
 * <ul>
 *   <li>重新跑 {@link CrossDayDependencyResolver}：若 RESOLVED → 调 {@link
 *       WorkflowNodeDispatchService#dispatchNode} 重新派发（创建 {@code run_seq+1} 的 READY 节点，原 WAITING
 *       行作为历史保留）；
 *   <li>仍 WAITING → 看 {@code workflow_node.cross_day_dependency_timeout_seconds}（0 / NULL 走默认配置
 *       {@link CrossDayDependencyReconcileProperties#getDefaultTimeoutSeconds}）；超时 → 节点推 FAILED + 发
 *       {@code BATCH_WORKFLOW_CROSS_DAY_DEP_TIMEOUT} 告警；
 *   <li>每次状态变更走 {@link AlertEventService} 形成审计与监控锚点。
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CrossDayDependencyReconciler {

  static final String STATUS_WAITING_DEPENDENCY = "WAITING_DEPENDENCY";
  static final String STATUS_FAILED = "FAILED";
  static final String ALERT_TYPE_TIMEOUT = "BATCH_WORKFLOW_CROSS_DAY_DEP_TIMEOUT";

  private final OrchestratorWorkflowMappers workflowMappers;
  private final OrchestratorJobMappers jobMappers;
  private final CrossDayDependencyResolver crossDayDependencyResolver;
  private final ObjectProvider<WorkflowNodeDispatchService> dispatchServiceProvider;
  private final AlertEventService alertEventService;
  private final CrossDayDependencyReconcileProperties properties;
  private final OrchestratorGracefulShutdown gracefulShutdown;
  private final BatchDateTimeSupport dateTimeSupport;

  @Scheduled(fixedDelayString = "${batch.workflow.cross-day-dep.poll-interval-millis:60000}")
  @SchedulerLock(name = "cross_day_dep_reconcile", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
  public void scheduledReconcile() {
    if (!properties.isEnabled() || gracefulShutdown.isDraining()) {
      return;
    }
    Instant now = dateTimeSupport.nowInstant();
    List<WorkflowNodeRunEntity> waiting =
        workflowMappers.workflowNodeRunMapper.selectByNodeStatus(
            STATUS_WAITING_DEPENDENCY, properties.getBatchSize());
    if (waiting == null || waiting.isEmpty()) {
      return;
    }
    for (WorkflowNodeRunEntity entry : waiting) {
      try {
        reconcileOne(entry, now);
      } catch (Exception failure) {
        log.warn(
            "cross_day_dep reconcile error: nodeRunId={}, nodeCode={}, msg={}",
            entry.getId(),
            entry.getNodeCode(),
            failure.getMessage());
      }
    }
  }

  void reconcileOne(WorkflowNodeRunEntity nodeRun, Instant now) {
    // workflow_node_run 实体本身没 tenantId 字段；用 RLS-bypass 的 selectByIdAnyTenant 先解出 tenant，
    // 之后所有 mapper / dispatchNode / alertEventService 调用都在 runWithTenant 内做，保证 RLS Phase B 通过。
    WorkflowRunEntity workflowRun = loadWorkflowRun(nodeRun.getWorkflowRunId());
    if (workflowRun == null || workflowRun.getRelatedJobInstanceId() == null) {
      return;
    }
    String tenantId = workflowRun.getTenantId();
    if (tenantId == null || tenantId.isBlank()) {
      return;
    }
    RlsTenantContextHolder.runWithTenant(
        tenantId, () -> reconcileOneBound(nodeRun, workflowRun, now));
  }

  /** 已绑定 RLS 后的真实 reconcile 主体。所有 mapper 调用都依赖此处的 holder。 */
  private void reconcileOneBound(
      WorkflowNodeRunEntity nodeRun, WorkflowRunEntity workflowRun, Instant now) {
    JobInstanceEntity jobInstance =
        jobMappers.jobInstanceMapper.selectById(
            workflowRun.getTenantId(), workflowRun.getRelatedJobInstanceId());
    if (jobInstance == null) {
      return;
    }
    WorkflowNodeEntity workflowNode =
        workflowMappers.workflowNodeMapper.selectByWorkflowDefinitionIdAndNodeCode(
            workflowRun.getWorkflowDefinitionId(), nodeRun.getNodeCode());
    if (workflowNode == null
        || workflowNode.getCrossDayDependencies() == null
        || workflowNode.getCrossDayDependencies().isBlank()) {
      // 依赖被人工清掉或节点定义改了 → 直接 fail（保守，避免无依据再 dispatch）
      markFailed(nodeRun, "CROSS_DAY_DEP_SPEC_MISSING", now);
      return;
    }
    ResolutionResult result =
        crossDayDependencyResolver.resolve(
            jobInstance.getTenantId(),
            jobInstance.getBizDate(),
            workflowNode.getCrossDayDependencies());
    if (result.isResolved()) {
      log.info(
          "cross_day_dep resolved: tenantId={}, workflowRunId={}, nodeCode={}",
          jobInstance.getTenantId(),
          workflowRun.getId(),
          nodeRun.getNodeCode());
      dispatchServiceProvider
          .getObject()
          .dispatchNode(
              jobInstance,
              workflowRun,
              new WorkflowDagService.DagNodeResolution(
                  nodeRun.getNodeCode(), nodeRun.getNodeType()),
              null,
              workflowRun.getTraceId());
      return;
    }
    if (result.isFailed()) {
      markFailed(nodeRun, result.getFailureCode(), now);
      emitTimeoutAlert(jobInstance, workflowRun, nodeRun, "FAILED:" + result.getFailureCode());
      return;
    }
    // WAITING — 看是否超时
    long timeoutSeconds = resolveTimeoutSeconds(workflowNode);
    if (timeoutSeconds <= 0L) {
      return;
    }
    if (nodeRun.getStartedAt() == null) {
      // 极少见：旧数据 / migration 残留没设 startedAt，跳过本轮等下次 dispatch 写入再判
      return;
    }
    Instant deadline = nodeRun.getStartedAt().plus(Duration.ofSeconds(timeoutSeconds));
    if (now.isAfter(deadline)) {
      markFailed(nodeRun, "CROSS_DAY_DEP_TIMEOUT", now);
      emitTimeoutAlert(jobInstance, workflowRun, nodeRun, "TIMEOUT");
    }
  }

  private WorkflowRunEntity loadWorkflowRun(Long workflowRunId) {
    return workflowMappers.workflowRunMapper.selectByIdAnyTenant(workflowRunId);
  }

  private long resolveTimeoutSeconds(WorkflowNodeEntity workflowNode) {
    Integer declared = workflowNode.getCrossDayDependencyTimeoutSeconds();
    if (declared == null || declared <= 0) {
      return properties.getDefaultTimeoutSeconds();
    }
    return declared;
  }

  private void markFailed(WorkflowNodeRunEntity nodeRun, String failureCode, Instant now) {
    workflowMappers.workflowNodeRunMapper.updateStatus(
        UpdateNodeRunStatusParam.builder()
            .id(nodeRun.getId())
            .nodeStatus(STATUS_FAILED)
            .errorCode(failureCode == null ? "CROSS_DAY_DEP_FAILED" : failureCode)
            .errorMessage("cross-day dependency reconcile fail")
            .durationMs(0L)
            .finishedAt(now)
            .build());
  }

  private void emitTimeoutAlert(
      JobInstanceEntity jobInstance,
      WorkflowRunEntity workflowRun,
      WorkflowNodeRunEntity nodeRun,
      String reason) {
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("tenantId", jobInstance.getTenantId());
    detail.put("workflowRunId", workflowRun.getId());
    detail.put("workflowDefinitionId", workflowRun.getWorkflowDefinitionId());
    detail.put("nodeCode", nodeRun.getNodeCode());
    detail.put(
        "bizDate", jobInstance.getBizDate() == null ? null : jobInstance.getBizDate().toString());
    detail.put("waitingReason", nodeRun.getErrorMessage());
    detail.put("reason", reason);
    String resourceKey =
        jobInstance.getTenantId() + ":" + workflowRun.getId() + ":" + nodeRun.getNodeCode();
    AlertEmitRequest request =
        AlertEmitRequest.builder()
            .tenantId(jobInstance.getTenantId())
            .serviceName("batch-orchestrator")
            .alertType(ALERT_TYPE_TIMEOUT)
            .severity("ERROR")
            .title("workflow cross-day dependency " + reason.toLowerCase(Locale.ROOT))
            .detailJson(JsonUtils.toJson(detail))
            .resourceKey(resourceKey)
            .traceId(workflowRun.getTraceId())
            .build();
    alertEventService.emit(request);
  }
}
