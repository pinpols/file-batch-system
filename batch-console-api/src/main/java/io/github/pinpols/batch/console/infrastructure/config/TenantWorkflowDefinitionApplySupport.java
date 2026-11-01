package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Nullables;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.WorkflowDefinitionSpec;
import io.github.pinpols.batch.console.domain.job.view.JobDefinitionView;
import io.github.pinpols.batch.console.domain.workflow.application.command.WorkflowVersionSnapshotCommand;
import io.github.pinpols.batch.console.domain.workflow.application.contract.request.WorkflowDefinitionSaveRequest;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowNodeEntity;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowDefinitionUpsertParam;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowDefinitionVersionUpdateParam;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowEdgeUpsertParam;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowNodeUpsertParam;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowNodeQuery;
import io.github.pinpols.batch.console.infrastructure.workflow.WorkflowDefinitionDagInspector;
import io.github.pinpols.batch.console.infrastructure.workflow.WorkflowDefinitionWriteSupport;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 配置初始化中的 Workflow 定义、完整图及版本快照写入。
 *
 * <p>所有读写沿用调用方逐项事务；图替换失败或版本 CAS 失败时仍整体回滚该条目。
 */
@Component
@RequiredArgsConstructor
final class TenantWorkflowDefinitionApplySupport {

  private final TenantDefinitionConfigMappers definitionMappers;
  private final WorkflowDefinitionWriteSupport workflowWriteSupport;
  private final WorkflowDefinitionDagInspector workflowDagInspector;

  private WorkflowDefinitionSaveRequest buildWorkflowVersionSnapshot(
      String tenantId,
      WorkflowDefinitionSpec spec,
      WorkflowDefinitionUpsertParam param,
      String scheduleType) {
    WorkflowDefinitionSaveRequest snapshot = new WorkflowDefinitionSaveRequest();
    snapshot.setTenantId(tenantId);
    snapshot.setWorkflowCode(spec.getWorkflowCode());
    snapshot.setWorkflowName(spec.getWorkflowName());
    snapshot.setWorkflowType(Nullables.coalesce(spec.getWorkflowType(), "DAG"));
    snapshot.setEnabled(Nullables.coalesce(spec.getEnabled(), true));
    snapshot.setScheduleType(scheduleType);
    snapshot.setScheduleExpr(spec.getScheduleExpr());
    snapshot.setTimezone(param.getTimezone());
    snapshot.setTriggerMode(param.getTriggerMode());
    snapshot.setCalendarCode(spec.getCalendarCode());
    snapshot.setDependsOnJobCode(spec.getDependsOnJobCode());
    return snapshot;
  }

  WorkflowDefinitionEntity findWorkflowForImport(String tenantId, WorkflowDefinitionSpec spec) {
    if (Texts.hasText(spec.getSingleJobCode())) {
      JobDefinitionView job = definitionMappers.jobDefinition.selectByUniqueKey(
          tenantId, spec.getSingleJobCode().trim());
      if (EmptyChecks.isNotNull(job)) {
        WorkflowDefinitionEntity managed =
            definitionMappers.workflowDefinition.selectBySingleJobDefinitionId(
                tenantId, job.getId());
        if (EmptyChecks.isNotNull(managed)) {
          return managed;
        }
      }
    }
    return definitionMappers.workflowDefinition.selectLatestByCode(
        tenantId, spec.getWorkflowCode());
  }

  void upsertWorkflowDefinition(
      String tenantId, Long existingId, WorkflowDefinitionSpec spec, String operator) {
    Long singleJobDefinitionId = null;
    if (Texts.hasText(spec.getSingleJobCode())) {
      JobDefinitionView singleJob = definitionMappers.jobDefinition.selectByUniqueKey(
          tenantId, spec.getSingleJobCode().trim());
      if (EmptyChecks.isNull(singleJob)) {
        throw BizException.of(
            ResultCode.NOT_FOUND,
            ResultCode.NOT_FOUND.detailKey(),
            "Managed Workflow Job not found: " + spec.getSingleJobCode());
      }
      singleJobDefinitionId = singleJob.getId();
    }
    WorkflowDefinitionUpsertParam param = new WorkflowDefinitionUpsertParam();
    param.setTenantId(tenantId);
    param.setWorkflowCode(spec.getWorkflowCode());
    param.setWorkflowName(spec.getWorkflowName());
    param.setWorkflowType(spec.getWorkflowType());
    WorkflowDefinitionEntity current = EmptyChecks.isNull(existingId)
        ? null
        : definitionMappers.workflowDefinition.selectById(tenantId, existingId);
    if (EmptyChecks.isNull(current) && EmptyChecks.isNotNull(singleJobDefinitionId)) {
      current = definitionMappers.workflowDefinition.selectBySingleJobDefinitionId(
          tenantId, singleJobDefinitionId);
    }
    String workflowCode =
        EmptyChecks.isNull(current) ? spec.getWorkflowCode() : current.getWorkflowCode();
    param.setWorkflowCode(workflowCode);
    param.setVersion(EmptyChecks.isNull(current) ? 1 : current.getVersion());
    param.setEnabled(EmptyChecks.isNotNull(spec.getEnabled()) && spec.getEnabled());
    param.setSingleJobDefinitionId(singleJobDefinitionId);
    param.setScheduleEnabled(
        EmptyChecks.isNotNull(spec.getScheduleEnabled()) && spec.getScheduleEnabled());
    String scheduleType = Nullables.coalesce(spec.getScheduleType(), "MANUAL");
    param.setScheduleType(scheduleType);
    param.setScheduleExpr(spec.getScheduleExpr());
    param.setTimezone(Nullables.coalesce(spec.getTimezone(), CommonConstants.DEFAULT_TIMEZONE_ID));
    String requestedTriggerMode = spec.getTriggerMode();
    String defaultTriggerMode =
        switch (scheduleType) {
          case "CRON", "FIXED_RATE" -> "SCHEDULED";
          case "EVENT" -> "EVENT";
          default -> "MANUAL";
        };
    String resolvedTriggerMode = Nullables.coalesce(requestedTriggerMode, defaultTriggerMode);
    param.setTriggerMode(resolvedTriggerMode);
    param.setCalendarCode(spec.getCalendarCode());
    param.setDependsOnJobCode(spec.getDependsOnJobCode());
    param.setCreatedBy(operator);
    param.setUpdatedBy(operator);
    definitionMappers.workflowDefinition.upsertWorkflowDefinition(param);

    WorkflowDefinitionEntity saved = EmptyChecks.isNull(current)
        ? definitionMappers.workflowDefinition.selectByUniqueKey(
            tenantId, workflowCode, param.getVersion())
        : current;
    if (EmptyChecks.isNull(saved)) {
      return;
    }
    Long defId = saved.getId();

    upsertWorkflowGraph(tenantId, defId, spec, EmptyChecks.isNotNull(existingId));

    boolean effectiveEnabled = EmptyChecks.isNull(current)
        ? Boolean.TRUE.equals(param.getEnabled())
        : Boolean.TRUE.equals(Nullables.coalesce(spec.getEnabled(), current.getEnabled()));
    if (effectiveEnabled || Boolean.TRUE.equals(param.getScheduleEnabled())) {
      WorkflowNodeQuery workflowNodeQuery = WorkflowNodeQuery.ofDefinition(tenantId, defId, null);
      List<WorkflowNodeEntity> workflowNodes =
          definitionMappers.workflowNode.selectByQuery(workflowNodeQuery);
      workflowDagInspector.requireExecutableJobReferences(tenantId, workflowNodes);
    }

    int nextVersion = EmptyChecks.isNull(current) ? 1 : current.getVersion() + 1;
    if (EmptyChecks.isNotNull(current)) {
      WorkflowDefinitionVersionUpdateParam versionedUpdate =
          WorkflowDefinitionVersionUpdateParam.builder()
              .tenantId(tenantId)
              .id(defId)
              .expectedVersion(current.getVersion())
              .workflowName(Nullables.coalesce(spec.getWorkflowName(), current.getWorkflowName()))
              .workflowType(Nullables.coalesce(spec.getWorkflowType(), current.getWorkflowType()))
              .enabled(Nullables.coalesce(spec.getEnabled(), current.getEnabled()))
              .scheduleEnabled(param.getScheduleEnabled())
              .scheduleType(scheduleType)
              .scheduleExpr(spec.getScheduleExpr())
              .timezone(param.getTimezone())
              .triggerMode(param.getTriggerMode())
              .calendarCode(spec.getCalendarCode())
              .dependsOnJobCode(spec.getDependsOnJobCode())
              .description(current.getDescription())
              .build();
      int rows = definitionMappers.workflowDefinition.updateAndBumpVersion(versionedUpdate);
      if (rows == 0) {
        throw BizException.of(
            ResultCode.CONFLICT,
            "error.workflow_full_update.version_conflict",
            current.getVersion(),
            current.getVersion());
      }
    }
    WorkflowDefinitionSaveRequest snapshot =
        buildWorkflowVersionSnapshot(tenantId, spec, param, scheduleType);
    WorkflowVersionSnapshotCommand snapshotCommand = WorkflowVersionSnapshotCommand.builder()
        .tenantId(tenantId)
        .definitionId(defId)
        .workflowCode(workflowCode)
        .version(nextVersion)
        .body(snapshot)
        .scheduleEnabled(param.getScheduleEnabled())
        .savedBy(operator)
        .build();
    workflowWriteSupport.appendVersionSnapshot(snapshotCommand);
  }

  /** 图的替换与节点/边写入保持在调用方同一事务中，随后仍由定义 CAS 决定整体提交。 */
  private void upsertWorkflowGraph(
      String tenantId, Long defId, WorkflowDefinitionSpec spec, boolean replaceExistingGraph) {
    if (EmptyChecks.isNotNull(spec.getNodes())) {
      // 旧图删除后无法再恢复掩码；先按目标租户和定义读取，不改写共享的多租户输入 spec。
      Map<String, String> existingNodeParams =
          replaceExistingGraph ? workflowWriteSupport.readNodeParams(tenantId, defId) : Map.of();
      if (replaceExistingGraph) {
        definitionMappers.workflowNode.deleteByWorkflowDefinitionId(defId);
        definitionMappers.workflowEdge.deleteByWorkflowDefinitionId(defId);
      }
      for (WorkflowDefinitionSpec.NodeSpec nodeSpec : spec.getNodes()) {
        WorkflowNodeUpsertParam nodeParam = new WorkflowNodeUpsertParam();
        nodeParam.setTenantId(tenantId);
        nodeParam.setWorkflowDefinitionId(defId);
        String nodeCode = nodeSpec.getNodeCode();
        nodeParam.setNodeCode(nodeCode);
        nodeParam.setNodeName(nodeSpec.getNodeName());
        nodeParam.setNodeType(nodeSpec.getNodeType());
        nodeParam.setRelatedJobCode(nodeSpec.getRelatedJobCode());
        nodeParam.setWorkerGroup(nodeSpec.getWorkerGroup());
        nodeParam.setWindowCode(nodeSpec.getWindowCode());
        nodeParam.setNodeOrder(nodeSpec.getNodeOrder());
        nodeParam.setRetryPolicy(nodeSpec.getRetryPolicy());
        nodeParam.setRetryMaxCount(nodeSpec.getRetryMaxCount());
        nodeParam.setTimeoutSeconds(nodeSpec.getTimeoutSeconds());
        String submittedNodeParams = nodeSpec.getNodeParams();
        String storedNodeParams = existingNodeParams.get(nodeCode);
        String resolvedNodeParams =
            TenantConfigMaskedParameters.resolve(submittedNodeParams, storedNodeParams);
        nodeParam.setNodeParams(resolvedNodeParams);
        nodeParam.setCrossDayDependencies(nodeSpec.getCrossDayDependencies());
        nodeParam.setCrossDayDependencyTimeoutSeconds(
            nodeSpec.getCrossDayDependencyTimeoutSeconds());
        nodeParam.setEnabled(Nullables.coalesce(nodeSpec.getEnabled(), true));
        definitionMappers.workflowNode.upsertWorkflowNode(nodeParam);
      }
      if (EmptyChecks.isNotNull(spec.getEdges())) {
        for (WorkflowDefinitionSpec.EdgeSpec edgeSpec : spec.getEdges()) {
          WorkflowEdgeUpsertParam edgeParam = new WorkflowEdgeUpsertParam();
          edgeParam.setTenantId(tenantId);
          edgeParam.setWorkflowDefinitionId(defId);
          edgeParam.setFromNodeCode(edgeSpec.getFromNodeCode());
          edgeParam.setToNodeCode(edgeSpec.getToNodeCode());
          edgeParam.setEdgeType(Nullables.coalesce(edgeSpec.getEdgeType(), "NORMAL"));
          edgeParam.setConditionExpr(edgeSpec.getConditionExpr());
          edgeParam.setEnabled(Nullables.coalesce(edgeSpec.getEnabled(), true));
          definitionMappers.workflowEdge.upsertWorkflowEdge(edgeParam);
        }
      }
    }
  }
}
