package com.example.batch.orchestrator.application.service.task;

import com.example.batch.common.constants.BatchStatusConstants;
import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.enums.WorkflowNodeRunStatus;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.persistence.entity.TriggerRequestEntity;
import com.example.batch.common.persistence.entity.WorkflowRunEntity;
import com.example.batch.common.utils.Guard;
import com.example.batch.common.utils.IdGenerator;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.application.service.workflow.OrchestratorWorkflowMappers;
import com.example.batch.orchestrator.application.service.workflow.WorkflowDagService;
import com.example.batch.orchestrator.application.service.workflow.WorkflowNodePayloadBuilder;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import com.example.batch.orchestrator.domain.query.JobPartitionQuery;
import com.example.batch.orchestrator.service.LaunchService;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * P2-4 god-class-decomposition extract: 把 {@link DefaultWorkflowNodeDispatchService} 的 JOB 节点
 * 子作业拉起逻辑全套抽到一处。
 *
 * <p>覆盖原 service ~175 行,主 service 不再夹带"造子作业"职责:
 *
 * <ul>
 *   <li>父 Job 内插入 virtual partition + virtual task(状态直接为 RUNNING),让父作业的分区聚合逻辑能统一处理子作业完成信号
 *   <li>写 trigger_request(供 LaunchValidationService.load() 加载)
 *   <li>构造子作业 LaunchRequest:剥离父侧 workflow 内部 key、合并 node_params、塞回指字段 ({@code _parentVirtualTaskId}
 *       等)
 * </ul>
 *
 * <p>子作业到达终态后,通过 {@code params_snapshot._parentVirtualTaskId} 回调到父侧的 {@code
 * DefaultTaskOutcomeService.signalParentVirtualTask},复用基于分区的 DAG 推进逻辑。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChildJobLaunchSupport {

  /**
   * sourcePayload 在 workflow 跨节点传递时会携带前一个节点写入的 workflow 内部字段({@code workflowNodeCode /
   * workflowNodeType / targetJobCode} 等)。这些字段是"当前节点"的标记,不应该泄露给下游节点的子作业 — 否则 EXPORT 节点的子作业会 看到
   * IMPORT 节点的 targetJobCode,用错 pipeline(表现为 "unsupported export stage code: RECEIVE")。
   */
  private static final Set<String> WORKFLOW_INTERNAL_PAYLOAD_KEYS =
      Set.of(
          "workflowNodeCode",
          "workflowNodeType",
          "targetJobCode",
          "_parentNodeCode",
          "_parentVirtualTaskId",
          "_parentWorkflowRunId",
          "parentInstanceId");

  /**
   * 跨 workflow 嵌套环检测上溯 parent_instance_id 链的硬上限。环本身在每次拉起前就会被拦截(见 {@link
   * #guardAgainstNestingCycle}),链上不会真正出现"不同 job_code 的环",故上溯必然终止于根;此上限只为给 rerun 长链 /
   * 异常数据兜底,避免万一脏数据导致无界 DB 读。命中视为异常但 fail-open(放行)——真正的环已在更早层被拦。
   */
  private static final int MAX_ANCESTOR_WALK = 256;

  private final OrchestratorJobMappers jobMappers;
  private final OrchestratorWorkflowMappers workflowMappers;
  private final ObjectProvider<TaskExecutionService> taskExecutionServiceProvider;
  private final ObjectProvider<LaunchService> launchServiceProvider;
  private final WorkflowNodePayloadBuilder payloadBuilder;

  /**
   * 通过启动子实例来分发 JOB 节点。在父 Job 中创建"虚拟"分区和任务(状态=RUNNING),使 {@code DefaultTaskOutcomeService} 中基于分区的
   * DAG 推进逻辑能统一处理子 Job 完成信号。 子 Job 到达终态后通过 params snapshot 中的 {@code _parentVirtualTaskId} 回调。
   */
  public int dispatchJobNode(
      JobInstanceEntity jobInstance,
      WorkflowRunEntity workflowRun,
      WorkflowDagService.DagNodeResolution node,
      WorkflowNodeEntity workflowNode,
      String sourcePayload,
      String traceId) {
    String refJobCode = workflowNode.getRelatedJobCode();
    if (refJobCode == null || refJobCode.isBlank()) {
      return 0;
    }

    // JOB 节点的 related_job_code 可指向 job_type=WORKFLOW 的 job(workflow 套 workflow)。
    // DefaultWorkflowDagService 的环检测只覆盖单 workflow_definition 内的 edge,发现不了
    // A→B→A 这类跨 workflow 引用环 —— 运行期会无限拉起子作业。这里在拉起任何副作用之前 fail-fast。
    guardAgainstNestingCycle(jobInstance, refJobCode, node.nodeCode());

    // 与 TASK/FILE_STEP 节点一致：立即将节点标为 READY
    recordNodeRunReady(workflowRun.getId(), node.nodeCode(), node.nodeType());

    String idempotencyKey =
        jobInstance.getTenantId() + ":wf:" + workflowRun.getId() + ":" + node.nodeCode();
    JobPartitionEntity virtualPartition =
        createVirtualPartition(jobInstance, node, refJobCode, idempotencyKey);
    JobTaskEntity virtualTask =
        createVirtualTask(jobInstance, node, refJobCode, virtualPartition, sourcePayload);
    incrementExpectedPartitionCount(jobInstance, node.nodeCode());

    // 为子作业写入 trigger_request，供 LaunchValidationService.load() 加载
    String childRequestId =
        writeTriggerRequestForChildJob(jobInstance, refJobCode, idempotencyKey, traceId);

    // 子作业启动参数含回指字段，便于子作业完成后回写本虚拟 task
    ChildLaunchContext launchCtx =
        ChildLaunchContext.builder()
            .jobInstance(jobInstance)
            .workflowRun(workflowRun)
            .node(node)
            .refJobCode(refJobCode)
            .sourcePayload(sourcePayload)
            .childRequestId(childRequestId)
            .traceId(traceId)
            .virtualTask(virtualTask)
            .workflowNode(workflowNode)
            .build();
    LaunchRequest childLaunchRequest = buildChildLaunchRequest(launchCtx);
    launchServiceProvider.getObject().launch(childLaunchRequest);

    return 1; // 向父 job 新增了一个虚拟分区
  }

  /**
   * 跨 workflow 嵌套环检测：在拉起子作业之前,沿 {@code parent_instance_id} 链上溯收集所有祖先 job_code(含当前父作业自身), 若待拉起的
   * {@code refJobCode} 已在祖先集合中,判定为环并 fail-fast。
   *
   * <p>因为判定发生在"每次拉起之前",一个不同 job_code 的环最多展开一层就会被拦截(A→B 拉起时 B 不在 {A} 中放行;B→A 拉起时 A 已在 {B,A}
   * 中被拦),所以正常链上不会真正出现环,上溯必然终止于根(parentInstanceId == null)。rerun 链虽会把同一 job_code 重复挂在 parent 链上,但收进
   * Set 后不产生误报。
   */
  private void guardAgainstNestingCycle(
      JobInstanceEntity parent, String refJobCode, String nodeCode) {
    Set<String> ancestorJobCodes = new LinkedHashSet<>();
    JobInstanceEntity cursor = parent;
    int hops = 0;
    while (cursor != null && hops < MAX_ANCESTOR_WALK) {
      if (cursor.getJobCode() != null) {
        ancestorJobCodes.add(cursor.getJobCode());
      }
      Long parentInstanceId = cursor.getParentInstanceId();
      if (parentInstanceId == null) {
        break;
      }
      cursor = jobMappers.jobInstanceMapper.selectById(cursor.getTenantId(), parentInstanceId);
      hops++;
    }
    if (hops >= MAX_ANCESTOR_WALK) {
      // fail-open：真正的环已在更早层被拦,这里只是兜底脏数据,放行但留痕便于排查。
      log.warn(
          "workflow nesting ancestor walk hit cap {} for tenant={} parentInstance={} node={};"
              + " cycle check may be incomplete",
          MAX_ANCESTOR_WALK,
          parent.getTenantId(),
          parent.getId(),
          nodeCode);
    }
    if (ancestorJobCodes.contains(refJobCode)) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          "error.workflow.nested_cycle_detected",
          refJobCode,
          nodeCode,
          String.join(" -> ", ancestorJobCodes));
    }
  }

  private JobPartitionEntity createVirtualPartition(
      JobInstanceEntity jobInstance,
      WorkflowDagService.DagNodeResolution node,
      String refJobCode,
      String idempotencyKey) {
    List<JobPartitionEntity> existingPartitions =
        jobMappers.jobPartitionMapper.selectByQuery(
            new JobPartitionQuery(jobInstance.getTenantId(), jobInstance.getId(), null, null));
    int virtualPartitionNo =
        existingPartitions.stream()
                .map(JobPartitionEntity::getPartitionNo)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0)
            + 1;

    JobPartitionEntity virtualPartition = new JobPartitionEntity();
    virtualPartition.setTenantId(jobInstance.getTenantId());
    virtualPartition.setJobInstanceId(jobInstance.getId());
    virtualPartition.setPartitionNo(virtualPartitionNo);
    virtualPartition.setPartitionKey(node.nodeCode() + ":JOB:" + virtualPartitionNo);
    virtualPartition.setPartitionStatus(PartitionStatus.RUNNING.code());
    virtualPartition.setVersion(0L);
    virtualPartition.setRetryCount(0);
    virtualPartition.setBusinessKey(refJobCode + ":" + node.nodeCode());
    // DBA-2026-05-20 P2-4: V124 partial UNIQUE 防 CLAIM 穿透依赖 idempotencyKey 非空,
    // 这里属于"上游构造非空,但跨函数边界容易回归"的位置,显式 Guard 兜底。
    Guard.requireText(idempotencyKey, "job_partition.idempotency_key (workflow virtual)");
    virtualPartition.setIdempotencyKey(idempotencyKey);
    virtualPartition.setDryRun(Boolean.TRUE.equals(jobInstance.getDryRun()));
    jobMappers.jobPartitionMapper.insert(virtualPartition);
    return virtualPartition;
  }

  private JobTaskEntity createVirtualTask(
      JobInstanceEntity jobInstance,
      WorkflowDagService.DagNodeResolution node,
      String refJobCode,
      JobPartitionEntity virtualPartition,
      String sourcePayload) {
    Map<String, Object> payloadMap =
        new LinkedHashMap<>(WorkflowNodePayloadBuilder.parsePayloadMap(sourcePayload));
    payloadMap.put("workflowNodeCode", node.nodeCode());
    payloadMap.put("workflowNodeType", node.nodeType());
    payloadMap.put("targetJobCode", refJobCode);
    String taskPayload = JsonUtils.toJson(payloadMap);

    JobTaskEntity virtualTaskTemplate = new JobTaskEntity();
    virtualTaskTemplate.setTenantId(jobInstance.getTenantId());
    virtualTaskTemplate.setJobInstanceId(jobInstance.getId());
    virtualTaskTemplate.setJobPartitionId(virtualPartition.getId());
    virtualTaskTemplate.setTaskType("EXECUTION");
    virtualTaskTemplate.setTaskSeq(1);
    virtualTaskTemplate.setTaskStatus(TaskStatus.RUNNING.code());
    virtualTaskTemplate.setVersion(0L);
    virtualTaskTemplate.setTaskPayload(taskPayload);
    virtualTaskTemplate.setDryRun(Boolean.TRUE.equals(jobInstance.getDryRun()));
    return taskExecutionServiceProvider.getObject().createTask(virtualTaskTemplate);
  }

  private void incrementExpectedPartitionCount(JobInstanceEntity jobInstance, String nodeCode) {
    int currentExpected =
        jobInstance.getExpectedPartitionCount() == null
            ? 0
            : jobInstance.getExpectedPartitionCount();
    int updated =
        jobMappers.jobInstanceMapper.updateExpectedPartitionCount(
            jobInstance.getTenantId(),
            jobInstance.getId(),
            currentExpected + 1,
            jobInstance.getVersion());
    Guard.require(
        updated > 0,
        ResultCode.STATE_CONFLICT,
        "job instance expected partition count update conflict for JOB node " + nodeCode);
    jobInstance.setExpectedPartitionCount(currentExpected + 1);
    jobInstance.setVersion((jobInstance.getVersion() == null ? 0L : jobInstance.getVersion()) + 1);
  }

  private String writeTriggerRequestForChildJob(
      JobInstanceEntity jobInstance, String refJobCode, String idempotencyKey, String traceId) {
    String childRequestId = IdGenerator.newTraceId();
    String childDedupKey = idempotencyKey + ":child";
    TriggerRequestEntity triggerRequest = new TriggerRequestEntity();
    triggerRequest.setTenantId(jobInstance.getTenantId());
    triggerRequest.setRequestId(childRequestId);
    triggerRequest.setTriggerType(TriggerType.EVENT.code());
    triggerRequest.setJobCode(refJobCode);
    triggerRequest.setBizDate(jobInstance.getBizDate());
    triggerRequest.setDedupKey(childDedupKey);
    triggerRequest.setRequestStatus(BatchStatusConstants.ACCEPTED);
    triggerRequest.setTraceId(traceId);
    jobMappers.triggerRequestMapper.insert(triggerRequest);
    return childRequestId;
  }

  private LaunchRequest buildChildLaunchRequest(ChildLaunchContext ctx) {
    Map<String, Object> parsed = WorkflowNodePayloadBuilder.parsePayloadMap(ctx.sourcePayload());
    Map<String, Object> childParams = new LinkedHashMap<>();
    parsed.forEach(
        (k, v) -> {
          if (!WORKFLOW_INTERNAL_PAYLOAD_KEYS.contains(k)) {
            childParams.put(k, v);
          }
        });
    // 与 TASK 节点的 buildTaskPayload 对齐：把 workflow_node.node_params 合并进子作业 launch
    // params。否则 JOB 节点在设计器里配的 templateCode / channelCode / seed payload 等字段
    // 永远无法传到子作业 job_instance.params_snapshot → worker 看不到 → import/export 凭空失败。
    payloadBuilder.mergeNodeParams(childParams, ctx.workflowNode(), ctx.workflowRun());
    childParams.put("parentInstanceId", ctx.jobInstance().getId());
    childParams.put("_parentVirtualTaskId", ctx.virtualTask().getId());
    childParams.put("_parentWorkflowRunId", ctx.workflowRun().getId());
    childParams.put("_parentNodeCode", ctx.node().nodeCode());
    return new LaunchRequest(
        ctx.jobInstance().getTenantId(),
        ctx.refJobCode(),
        ctx.jobInstance().getBizDate(),
        TriggerType.EVENT,
        ctx.childRequestId(),
        ctx.traceId(),
        childParams);
  }

  // ── 与主 service 共享的 node_run 写入助手 ────────────────────────────────
  // 这里复刻了主 service 的 recordNodeRunReady / nextRunSeq(各 ~12 LOC),原因:
  // 跨类共享需要再抽一个 NodeRunSupport 类或在 service 上暴露 public 方法,
  // 二选一都增加耦合;复刻 25 行是更小的代价。

  private void recordNodeRunReady(Long workflowRunId, String nodeCode, String nodeType) {
    WorkflowNodeRunEntity readyNode = new WorkflowNodeRunEntity();
    readyNode.setWorkflowRunId(workflowRunId);
    readyNode.setNodeCode(nodeCode);
    readyNode.setNodeType(nodeType);
    readyNode.setRunSeq(nextRunSeq(workflowRunId, nodeCode));
    readyNode.setNodeStatus(WorkflowNodeRunStatus.READY.code());
    readyNode.setRetryCount(0);
    readyNode.setDurationMs(0L);
    workflowMappers.workflowNodeRunMapper.insert(readyNode);
  }

  private int nextRunSeq(Long workflowRunId, String nodeCode) {
    WorkflowNodeRunEntity latestNodeRun =
        workflowMappers.workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(
            workflowRunId, nodeCode);
    return latestNodeRun == null || latestNodeRun.getRunSeq() == null
        ? 1
        : latestNodeRun.getRunSeq() + 1;
  }

  @Builder
  private record ChildLaunchContext(
      JobInstanceEntity jobInstance,
      WorkflowRunEntity workflowRun,
      WorkflowDagService.DagNodeResolution node,
      String refJobCode,
      String sourcePayload,
      String childRequestId,
      String traceId,
      JobTaskEntity virtualTask,
      WorkflowNodeEntity workflowNode) {}
}
