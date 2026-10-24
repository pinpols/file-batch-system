package io.github.pinpols.batch.orchestrator.application.service.task;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TriggerRequestStatus;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.common.utils.IdGenerator;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.service.WorkflowNodeRunSupport;
import io.github.pinpols.batch.orchestrator.application.service.workflow.OrchestratorWorkflowMappers;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowDagService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowNodePayloadBuilder;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunExecutionSnapshot;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.service.LaunchService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** 启动由 Workflow 节点拥有的子 Job，不创建虚构的父 Job 状态。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChildJobLaunchSupport {

  private static final Set<String> WORKFLOW_INTERNAL_PAYLOAD_KEYS = Set.of(
      "workflowNodeCode",
      "workflowNodeType",
      "targetJobCode",
      "_parentNodeCode",
      "_parentNodeRunId",
      "_parentWorkflowRunId");

  private final OrchestratorJobMappers jobMappers;
  private final OrchestratorWorkflowMappers workflowMappers;
  private final ObjectProvider<LaunchService> launchServiceProvider;
  private final WorkflowNodePayloadBuilder payloadBuilder;
  private final SecretPayloadProtector secretPayloadProtector;

  /** 启动 READY JOB 节点引用的 Job，并将其关联到对应节点运行记录。 */
  public int dispatchJobNode(
      WorkflowRunEntity workflowRun,
      WorkflowDagService.DagNodeResolution node,
      WorkflowNodeEntity workflowNode,
      String sourcePayload,
      String traceId) {
    if (EmptyChecks.isNull(workflowRun) || EmptyChecks.isBlank(workflowNode.getRelatedJobCode())) {
      return 0;
    }
    String sourcePayloadProtected =
        EmptyChecks.isBlank(sourcePayload) ? null : secretPayloadProtector.protect(sourcePayload);
    WorkflowNodeRunEntity nodeRun = WorkflowNodeRunSupport.recordReadyIfAbsent(
        workflowMappers.workflowNodeRunMapper,
        workflowRun.getId(),
        node.nodeCode(),
        node.nodeType(),
        sourcePayloadProtected);
    if (EmptyChecks.isNull(nodeRun)) {
      return 0;
    }
    String idempotencyKey =
        workflowRun.getTenantId() + ":wf:" + workflowRun.getId() + ":" + node.nodeCode();
    WorkflowRunExecutionSnapshot executionSnapshot = WorkflowRunExecutionSnapshot.from(workflowRun);
    // 同一根运行派发的所有节点共享 trace ID；每个子请求
    // 仍需有独立数据库标识，即使并行节点共享该 trace。
    String childRequestId = IdGenerator.newBusinessNo("child");
    String childDedupKey = resolveChildDedupKey(workflowRun, idempotencyKey);
    TriggerRequestEntity triggerRequest = new TriggerRequestEntity();
    triggerRequest.setTenantId(workflowRun.getTenantId());
    triggerRequest.setRequestId(childRequestId);
    triggerRequest.setTriggerType(TriggerType.EVENT.code());
    triggerRequest.setJobCode(workflowNode.getRelatedJobCode());
    triggerRequest.setBizDate(workflowRun.getBizDate());
    triggerRequest.setDedupKey(childDedupKey);
    triggerRequest.setRequestStatus(TriggerRequestStatus.ACCEPTED.code());
    triggerRequest.setTraceId(traceId);
    triggerRequest.setDryRun(Boolean.TRUE.equals(workflowRun.getDryRun()));
    triggerRequest.setParentWorkflowRunId(workflowRun.getId());
    triggerRequest.setParentNodeCode(node.nodeCode());

    Map<String, Object> childParams = new LinkedHashMap<>();
    WorkflowNodePayloadBuilder.parsePayloadMap(sourcePayload).forEach((key, value) -> {
      if (!WORKFLOW_INTERNAL_PAYLOAD_KEYS.contains(key)) {
        childParams.put(key, value);
      }
    });
    payloadBuilder.mergeNodeParams(childParams, workflowNode, workflowRun);
    if (EmptyChecks.isNotNull(executionSnapshot)
        && EmptyChecks.isNotNull(executionSnapshot.replayOperationId())) {
      childParams.put("operationType", "WORKFLOW_RUN");
    }
    pinSystemPipelineIdentity(childParams, executionSnapshot, node.nodeCode());
    childParams.put("_parentWorkflowRunId", workflowRun.getId());
    childParams.put("_parentNodeCode", node.nodeCode());
    childParams.put("_parentNodeRunId", nodeRun.getId());
    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(workflowRun.getTenantId())
        .jobCode(workflowNode.getRelatedJobCode())
        .bizDate(workflowRun.getBizDate())
        .dataIntervalStart(workflowRun.getDataIntervalStart())
        .dataIntervalEnd(workflowRun.getDataIntervalEnd())
        .triggerType(TriggerType.EVENT)
        .requestId(childRequestId)
        .traceId(traceId)
        .params(childParams)
        .replaySessionId(replaySessionId(executionSnapshot))
        .dryRun(Boolean.TRUE.equals(workflowRun.getDryRun()))
        .build();
    triggerRequest.setLaunchPayload(JsonUtils.toJson(launchRequest));
    if (jobMappers.triggerRequestMapper.insert(triggerRequest) != 1) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.child_job_launch_rejected");
    }
    LaunchResponse response;
    try {
      response = launchServiceProvider.getObject().launch(launchRequest);
    } catch (BizException rejection) {
      TriggerRequestEntity rejectedRequest =
          jobMappers.triggerRequestMapper.selectByTenantAndRequestId(
              workflowRun.getTenantId(), childRequestId);
      if (EmptyChecks.isNotNull(rejectedRequest)
          && TriggerRequestStatus.REJECTED.code().equals(rejectedRequest.getRequestStatus())) {
        throw new RejectedChildJobLaunchException(nodeRun.getId(), rejection);
      }
      throw rejection;
    }
    if (isWaitingForBatchDay(response, workflowRun.getTenantId(), childRequestId)) {
      return 0;
    }
    Guard.require(
        EmptyChecks.isNotNull(response) && !EmptyChecks.isBlank(response.instanceNo()),
        ResultCode.STATE_CONFLICT,
        "error.workflow.child_job_instance_missing");
    JobInstanceEntity childInstance = jobMappers.jobInstanceMapper.selectByInstanceNo(
        workflowRun.getTenantId(), response.instanceNo());
    Guard.require(
        EmptyChecks.isNotNull(childInstance) && EmptyChecks.isNotNull(childInstance.getId()),
        ResultCode.STATE_CONFLICT,
        "error.workflow.child_job_instance_missing");
    int linked = workflowMappers.workflowNodeRunMapper.attachJobInstance(
        workflowRun.getTenantId(), nodeRun.getId(), childInstance.getId());
    Guard.require(
        linked > 0, ResultCode.STATE_CONFLICT, "error.workflow.node_job_instance_link_conflict");
    return 1;
  }

  private String resolveChildDedupKey(WorkflowRunEntity workflowRun, String idempotencyKey) {
    if (EmptyChecks.isNull(workflowRun.getSingleJobDefinitionId())) {
      return idempotencyKey + ":child";
    }
    TriggerRequestEntity rootRequest = jobMappers.triggerRequestMapper.selectById(
        workflowRun.getTenantId(), workflowRun.getTriggerRequestId());
    if (EmptyChecks.isNotNull(rootRequest) && EmptyChecks.isNotBlank(rootRequest.getDedupKey())) {
      return rootRequest.getDedupKey();
    }
    return idempotencyKey + ":child";
  }

  private Long replaySessionId(WorkflowRunExecutionSnapshot executionSnapshot) {
    return EmptyChecks.isNull(executionSnapshot) ? null : executionSnapshot.replaySessionId();
  }

  /** 尚未创建 Job Instance 的延迟子作业应保持节点 READY，等待释放调度器处理。 */
  private boolean isWaitingForBatchDay(
      LaunchResponse response, String tenantId, String childRequestId) {
    if (EmptyChecks.isNotNull(response) && !EmptyChecks.isBlank(response.instanceNo())) {
      return false;
    }
    TriggerRequestEntity triggerRequest =
        jobMappers.triggerRequestMapper.selectByTenantAndRequestId(tenantId, childRequestId);
    if (EmptyChecks.isNotNull(triggerRequest)
        && TriggerRequestStatus.WAITING.code().equals(triggerRequest.getRequestStatus())) {
      return true;
    }
    throw BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.child_job_launch_rejected");
  }

  private void pinSystemPipelineIdentity(
      Map<String, Object> childParams,
      WorkflowRunExecutionSnapshot executionSnapshot,
      String nodeCode) {
    if (EmptyChecks.isNotNull(executionSnapshot)) {
      executionSnapshot.copySystemPipelineIdentity(nodeCode, childParams);
    }
  }

  /** 实例创建前被明确拒绝的子请求可记录为节点失败。 */
  public static final class RejectedChildJobLaunchException extends RuntimeException {

    private final Long nodeRunId;
    private final BizException rejection;

    public RejectedChildJobLaunchException(Long nodeRunId, BizException rejection) {
      super(rejection.getMessage(), rejection);
      this.nodeRunId = nodeRunId;
      this.rejection = rejection;
    }

    public Long nodeRunId() {
      return nodeRunId;
    }

    public BizException rejection() {
      return rejection;
    }
  }
}
