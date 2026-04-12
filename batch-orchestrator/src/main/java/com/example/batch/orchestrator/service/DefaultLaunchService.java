package com.example.batch.orchestrator.service;

import com.example.batch.common.constants.BatchStatusConstants;
import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.JobInstanceStatus;
import com.example.batch.common.enums.JobType;
import com.example.batch.common.enums.WorkflowNodeCode;
import com.example.batch.common.enums.WorkflowNodeRunStatus;
import com.example.batch.common.enums.WorkflowNodeType;
import com.example.batch.common.enums.WorkflowRunStatus;
import com.example.batch.common.persistence.entity.WorkflowRunEntity;
import com.example.batch.common.utils.IdGenerator;
import com.example.batch.orchestrator.application.service.OrchestratorJobMappers;
import com.example.batch.orchestrator.application.service.OrchestratorWorkflowMappers;
import com.example.batch.orchestrator.application.service.PartitionDispatchService;
import com.example.batch.orchestrator.application.service.WorkflowDagService;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import com.example.batch.orchestrator.service.LaunchValidationService.LaunchLoadResult;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 批任务启动（Launch）的核心入口：把一次触发请求落地为可调度的运行态，并驱动后续分片/任务派发。
 *
 * <p>这里把 launch 拆成两段<strong>独立提交</strong>的事务（T1/T2），目的是降低锁竞争与提升可重试性：
 *
 * <ul>
 *   <li><strong>T1（准备态落库）</strong>：只创建 {@code job_instance}/{@code workflow_run} 以及 START 节点的运行态，
 *       快速提交，作为后续调度/派发的"事实源"。
 *   <li><strong>T2（运行态构建与派发）</strong>：创建 partition/task、写 outbox，并推进 instance/workflow 状态。 高竞争表只在
 *       T2 短事务里触碰，避免长事务持锁。
 * </ul>
 *
 * <p>注意：{@link #prepareJobInstance} 必须通过 self-proxy 调用，才能让 Spring AOP 的 {@code @Transactional} 生效。
 */
@Service
@RequiredArgsConstructor
public class DefaultLaunchService implements LaunchService {

  private final LaunchValidationService launchValidationService;
  private final PartitionDispatchService partitionDispatchService;
  private final OrchestratorJobMappers jobMappers;
  private final OrchestratorWorkflowMappers workflowMappers;
  private final WorkflowDagService workflowDagService;
  private final LaunchBatchDayService launchBatchDayService;
  private final LaunchParamResolver launchParamResolver;
  private final ObjectProvider<DefaultLaunchService> selfProvider;

  @Override
  public LaunchResponse launch(LaunchRequest request) {
    LaunchLoadResult loaded = launchValidationService.load(request);
    if (loaded.existingInstance() != null) {
      jobMappers.triggerRequestMapper.updateAcceptance(
          request.tenantId(),
          request.requestId(),
          BatchStatusConstants.DUPLICATE,
          loaded.existingInstance().getId());
      return new LaunchResponse(
          loaded.existingInstance().getInstanceNo(), loaded.existingInstance().getTraceId());
    }

    String traceId =
        request.traceId() == null || request.traceId().isBlank()
            ? IdGenerator.newTraceId()
            : request.traceId();
    LaunchRequest routedRequest = launchBatchDayService.routeLateArrivalIfNeeded(request, loaded);
    Map<String, Object> effectiveParams =
        launchParamResolver.mergeLaunchParams(
            loaded.jobDefinition(), routedRequest.triggerType(), routedRequest.params());

    // T1：先把 instance/workflow 落库并提交，避免 T2 执行期间持有更长时间锁。
    PreparedLaunch prepared;
    try {
      prepared =
          selfProvider
              .getObject()
              .prepareJobInstance(routedRequest, loaded, effectiveParams, traceId);
    } catch (DuplicateKeyException exception) {
      return resolveConcurrentDuplicate(request, loaded, exception);
    } catch (DataIntegrityViolationException exception) {
      return resolveConcurrentDuplicate(request, loaded, exception);
    } catch (RuntimeException exception) {
      // PG 唯一约束等可能被包装为 TransactionSystemException / UncategorizedDataAccess 等，需沿 cause 识别
      // 23505
      if (hasSqlStateInChain(exception, "23505")) {
        return resolveConcurrentDuplicate(request, loaded, exception);
      }
      throw exception;
    }

    // T2：构建分片/任务/outbox，并推进运行态；该事务可在失败后独立重试。
    partitionDispatchService.dispatch(
        PartitionDispatchService.DispatchContext.of(
            new PartitionDispatchService.DispatchRequest(request, effectiveParams, traceId),
            new PartitionDispatchService.DispatchRuntime(
                prepared.jobInstance(),
                prepared.workflowRun(),
                prepared.initialNodes(),
                prepared.startedAt())));

    jobMappers.triggerRequestMapper.updateAcceptance(
        request.tenantId(),
        request.requestId(),
        BatchStatusConstants.LAUNCHED,
        prepared.jobInstance().getId());
    return new LaunchResponse(prepared.jobInstance().getInstanceNo(), traceId);
  }

  /**
   * T1 事务：创建 {@code job_instance}/{@code workflow_run}，并补齐 START 节点运行态。
   *
   * <p>该事务只做"准备态落库"，不触碰高竞争的 task/partition/outbox 表，从而缩短锁持有时间。
   */
  @Transactional
  public PreparedLaunch prepareJobInstance(
      LaunchRequest request,
      LaunchLoadResult loaded,
      Map<String, Object> effectiveParams,
      String traceId) {
    JobInstanceEntity jobInstance = new JobInstanceEntity();
    jobInstance.setTenantId(request.tenantId());
    jobInstance.setJobDefinitionId(loaded.jobDefinition().id());
    jobInstance.setTriggerRequestId(loaded.triggerRequest().getId());
    jobInstance.setJobCode(request.jobCode());
    jobInstance.setInstanceNo(IdGenerator.newBusinessNo("inst"));
    jobInstance.setBizDate(request.bizDate());
    jobInstance.setTriggerType(request.triggerType().code());
    jobInstance.setInstanceStatus(JobInstanceStatus.CREATED.code());
    jobInstance.setBatchNo(launchParamResolver.resolveBatchNo(request.bizDate(), effectiveParams));
    jobInstance.setOperatorId(LaunchParamResolver.resolveOperatorId(effectiveParams));
    jobInstance.setRerunFlag(
        launchParamResolver.resolveRerunFlag(request.triggerType(), effectiveParams));
    jobInstance.setRetryFlag(launchParamResolver.resolveRetryFlag(effectiveParams));
    jobInstance.setRerunReason(launchParamResolver.resolveRerunReason(effectiveParams));
    jobInstance.setRelatedFileId(launchParamResolver.resolveRelatedFileId(effectiveParams));
    jobInstance.setParentInstanceId(launchParamResolver.resolveParentInstanceId(effectiveParams));
    jobInstance.setQueueCode(loaded.jobDefinition().queueCode());
    jobInstance.setWorkerGroup(loaded.jobDefinition().workerGroup());
    jobInstance.setPriority(
        loaded.jobDefinition().priority() == null ? 5 : loaded.jobDefinition().priority());
    jobInstance.setDedupKey(loaded.triggerRequest().getDedupKey());
    jobInstance.setVersion(0L);
    jobInstance.setExpectedPartitionCount(0);
    jobInstance.setSuccessPartitionCount(0);
    jobInstance.setFailedPartitionCount(0);
    jobInstance.setTraceId(traceId);
    jobInstance.setParamsSnapshot(
        launchParamResolver.buildParamsSnapshot(
            loaded.jobDefinition(), request, effectiveParams, traceId));
    jobInstance.setResultSummary(null);
    Instant batchDaySlaDeadlineAt =
        launchBatchDayService.resolveBatchDaySlaDeadlineAt(
            request.tenantId(), loaded.jobDefinition().calendarCode(), request.bizDate());
    Instant createdAt = Instant.now();
    jobInstance.setDeadlineAt(
        launchParamResolver.resolveDeadlineAt(
            createdAt,
            request.bizDate(),
            loaded.jobDefinition(),
            effectiveParams,
            batchDaySlaDeadlineAt));
    jobInstance.setExpectedDurationSeconds(
        launchParamResolver.resolveExpectedDurationSeconds(
            loaded.jobDefinition(), effectiveParams));
    jobInstance.setSlaAlertedAt(null);
    jobMappers.jobInstanceMapper.insert(jobInstance);
    launchBatchDayService.upsertBatchDayInstance(
        request, loaded.jobDefinition(), effectiveParams, batchDaySlaDeadlineAt);

    // WORKFLOW 类型：解析 DAG 初始节点并创建 workflow_run/node_run；其他类型（IMPORT/EXPORT/DISPATCH/GENERAL）无
    // workflow，跳过。
    List<WorkflowDagService.DagNodeResolution> initialNodes;
    WorkflowRunEntity workflowRun;
    Instant startedAt = Instant.now();

    if (JobType.WORKFLOW.code().equals(loaded.jobDefinition().jobType())) {
      initialNodes =
          workflowDagService.resolveInitialNodes(
              loaded.workflowDefinition().id(),
              launchParamResolver.buildPayloadJson(effectiveParams));

      workflowRun = new WorkflowRunEntity();
      workflowRun.setTenantId(request.tenantId());
      workflowRun.setWorkflowDefinitionId(loaded.workflowDefinition().id());
      workflowRun.setRelatedJobInstanceId(jobInstance.getId());
      workflowRun.setBizDate(request.bizDate());
      workflowRun.setRunStatus(WorkflowRunStatus.CREATED.code());
      workflowRun.setCurrentNodeCode(resolveInitialCurrentNode(initialNodes));
      workflowRun.setTraceId(traceId);
      workflowMappers.workflowRunMapper.insert(workflowRun);

      WorkflowNodeRunEntity startNodeRun = new WorkflowNodeRunEntity();
      startNodeRun.setWorkflowRunId(workflowRun.getId());
      startNodeRun.setNodeCode(WorkflowNodeCode.START.code());
      startNodeRun.setNodeType(WorkflowNodeType.START.code());
      startNodeRun.setRunSeq(1);
      startNodeRun.setNodeStatus(WorkflowNodeRunStatus.SUCCESS.code());
      startNodeRun.setRetryCount(0);
      startNodeRun.setDurationMs(0L);
      startNodeRun.setStartedAt(startedAt);
      startNodeRun.setFinishedAt(startedAt);
      workflowMappers.workflowNodeRunMapper.insert(startNodeRun);
    } else {
      initialNodes = List.of();
      workflowRun = null;
    }

    return new PreparedLaunch(jobInstance, workflowRun, initialNodes, startedAt);
  }

  // ── 辅助方法 ─────────────────────────────────────────────────────────────────

  private String resolveInitialCurrentNode(
      List<WorkflowDagService.DagNodeResolution> initialNodes) {
    if (initialNodes == null || initialNodes.isEmpty()) {
      return WorkflowNodeCode.START.code();
    }
    Set<String> activeNodes = new LinkedHashSet<>();
    for (WorkflowDagService.DagNodeResolution node : initialNodes) {
      if (node == null || WorkflowNodeCode.START.code().equals(node.nodeCode())) {
        continue;
      }
      activeNodes.add(node.nodeCode());
    }
    return activeNodes.isEmpty() ? WorkflowNodeCode.START.code() : String.join(",", activeNodes);
  }

  private static boolean hasSqlStateInChain(Throwable throwable, String sqlState) {
    for (Throwable t = throwable; t != null; t = t.getCause()) {
      if (t instanceof SQLException sql) {
        if (sqlState.equals(sql.getSQLState())) {
          return true;
        }
        String m = sql.getMessage();
        if (m != null && m.contains("uk_job_instance_tenant_dedup")) {
          return true;
        }
      }
    }
    return false;
  }

  private LaunchResponse resolveConcurrentDuplicate(
      LaunchRequest request, LaunchLoadResult loaded, RuntimeException exception) {
    JobInstanceEntity existingInstance =
        jobMappers.jobInstanceMapper.selectByTenantAndDedupKey(
            request.tenantId(), loaded.triggerRequest().getDedupKey());
    if (existingInstance == null) {
      throw exception;
    }
    jobMappers.triggerRequestMapper.updateAcceptance(
        request.tenantId(),
        request.requestId(),
        BatchStatusConstants.DUPLICATE,
        existingInstance.getId());
    return new LaunchResponse(existingInstance.getInstanceNo(), existingInstance.getTraceId());
  }

  record PreparedLaunch(
      JobInstanceEntity jobInstance,
      WorkflowRunEntity workflowRun,
      List<WorkflowDagService.DagNodeResolution> initialNodes,
      Instant startedAt) {}
}
