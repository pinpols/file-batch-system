package com.example.batch.orchestrator.service;

import com.example.batch.common.constants.BatchStatusConstants;
import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.JobInstanceStatus;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.enums.WorkflowNodeCode;
import com.example.batch.common.enums.WorkflowNodeRunStatus;
import com.example.batch.common.enums.WorkflowRunStatus;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.IdGenerator;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import com.example.batch.orchestrator.application.plan.SchedulePlan;
import com.example.batch.orchestrator.application.plan.SchedulePlanBuilder;
import com.example.batch.orchestrator.application.plan.SchedulePlanCommand;
import com.example.batch.orchestrator.application.service.PartitionLifecycleService;
import com.example.batch.orchestrator.application.service.TaskExecutionService;
import com.example.batch.orchestrator.application.service.WorkflowDagService;
import com.example.batch.orchestrator.application.service.WorkflowNodeDispatchService;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.entity.JobDefinitionRecord;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.domain.entity.TriggerRequestEntity;
import com.example.batch.orchestrator.mapper.TriggerRequestMapper;
import com.example.batch.orchestrator.domain.entity.WorkflowRunEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowDefinitionRecord;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import com.example.batch.orchestrator.domain.statemachine.StateMachine;
import com.example.batch.orchestrator.mapper.WorkflowRunMapper;
import com.example.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import com.example.batch.orchestrator.application.scheduler.ResourceScheduler;
import com.example.batch.orchestrator.domain.scheduler.ResourceSchedulingDecision;
import com.example.batch.orchestrator.domain.scheduler.ResourceSchedulingRequest;
import com.example.batch.orchestrator.repository.JobDefinitionRepository;
import com.example.batch.orchestrator.repository.WorkflowDefinitionRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DefaultLaunchService implements LaunchService {

    private final TriggerRequestMapper triggerRequestMapper;
    private final JobDefinitionRepository jobDefinitionRepository;
    private final WorkflowDefinitionRepository workflowDefinitionRepository;
    private final JobInstanceMapper jobInstanceMapper;
    private final WorkflowRunMapper workflowRunMapper;
    private final WorkflowNodeRunMapper workflowNodeRunMapper;
    private final SchedulePlanBuilder schedulePlanBuilder;
    private final PartitionLifecycleService partitionLifecycleService;
    private final TaskExecutionService taskExecutionService;
    private final TaskDispatchOutboxService taskDispatchOutboxService;
    private final StateMachine<Object> stateMachine;
    private final WorkflowDagService workflowDagService;
    private final WorkflowNodeDispatchService workflowNodeDispatchService;
    private final ResourceScheduler resourceScheduler;

    @Override
    @Transactional
    public LaunchResponse launch(LaunchRequest request) {
        validate(request);
        TriggerRequestEntity triggerRequest = triggerRequestMapper.selectByTenantAndRequestId(request.tenantId(), request.requestId());
        if (triggerRequest == null) {
            throw new BizException(ResultCode.NOT_FOUND, "trigger request not found");
        }

        JobDefinitionRecord jobDefinition = jobDefinitionRepository.findFirstByTenantIdAndJobCodeAndEnabled(request.tenantId(), request.jobCode(), true);
        if (jobDefinition == null) {
            triggerRequestMapper.updateAcceptance(request.tenantId(), request.requestId(), BatchStatusConstants.REJECTED, null);
            throw new BizException(ResultCode.NOT_FOUND, "job definition not found");
        }

        WorkflowDefinitionRecord workflowDefinition = workflowDefinitionRepository
                .findFirstByTenantIdAndWorkflowCodeAndEnabled(request.tenantId(), request.jobCode(), true);
        if (workflowDefinition == null) {
            triggerRequestMapper.updateAcceptance(request.tenantId(), request.requestId(), BatchStatusConstants.REJECTED, null);
            throw new BizException(ResultCode.NOT_FOUND, "workflow definition not found for job code");
        }

        JobInstanceEntity existing = jobInstanceMapper.selectByTenantAndDedupKey(request.tenantId(), triggerRequest.getDedupKey());
        if (existing != null) {
            triggerRequestMapper.updateAcceptance(request.tenantId(), request.requestId(), BatchStatusConstants.DUPLICATE, existing.getId());
            return new LaunchResponse(existing.getInstanceNo(), existing.getTraceId());
        }

        String traceId = request.traceId() == null || request.traceId().isBlank() ? IdGenerator.newTraceId() : request.traceId();
        Map<String, Object> effectiveParams = mergeLaunchParams(jobDefinition, request.params());

        JobInstanceEntity jobInstance = new JobInstanceEntity();
        jobInstance.setTenantId(request.tenantId());
        jobInstance.setJobDefinitionId(jobDefinition.getId());
        jobInstance.setTriggerRequestId(triggerRequest.getId());
        jobInstance.setJobCode(request.jobCode());
        jobInstance.setInstanceNo(IdGenerator.newBusinessNo("inst"));
        jobInstance.setBizDate(request.bizDate());
        jobInstance.setTriggerType(request.triggerType().code());
        jobInstance.setInstanceStatus(JobInstanceStatus.CREATED.code());
        jobInstance.setBatchNo(resolveBatchNo(request.bizDate(), effectiveParams));
        jobInstance.setOperatorId(resolveOperatorId(effectiveParams));
        jobInstance.setRerunFlag(resolveRerunFlag(request.triggerType(), effectiveParams));
        jobInstance.setRetryFlag(resolveRetryFlag(effectiveParams));
        jobInstance.setRerunReason(resolveRerunReason(effectiveParams));
        jobInstance.setRelatedFileId(resolveRelatedFileId(effectiveParams));
        jobInstance.setParentInstanceId(resolveParentInstanceId(effectiveParams));
        jobInstance.setQueueCode(jobDefinition.getQueueCode());
        jobInstance.setWorkerGroup(jobDefinition.getWorkerGroup());
        jobInstance.setPriority(jobDefinition.getPriority() == null ? 5 : jobDefinition.getPriority());
        jobInstance.setDedupKey(triggerRequest.getDedupKey());
        jobInstance.setVersion(0L);
        jobInstance.setExpectedPartitionCount(0);
        jobInstance.setSuccessPartitionCount(0);
        jobInstance.setFailedPartitionCount(0);
        jobInstance.setTraceId(traceId);
        jobInstance.setParamsSnapshot(buildParamsSnapshot(jobDefinition, request, effectiveParams, traceId));
        jobInstance.setResultSummary(null);
        jobInstance.setDeadlineAt(resolveDeadlineAt(request.bizDate(), effectiveParams));
        jobInstance.setExpectedDurationSeconds(resolveExpectedDurationSeconds(jobDefinition, effectiveParams));
        jobInstance.setSlaAlertedAt(null);
        jobInstanceMapper.insert(jobInstance);

        WorkflowRunEntity workflowRun = new WorkflowRunEntity();
        workflowRun.setTenantId(request.tenantId());
        workflowRun.setWorkflowDefinitionId(workflowDefinition.getId());
        workflowRun.setRelatedJobInstanceId(jobInstance.getId());
        workflowRun.setBizDate(request.bizDate());
        workflowRun.setRunStatus(WorkflowRunStatus.CREATED.code());
        List<WorkflowDagService.DagNodeResolution> initialNodes = workflowDagService.resolveInitialNodes(
                workflowDefinition.getId(),
                buildPayloadJson(effectiveParams)
        );
        workflowRun.setCurrentNodeCode(resolveInitialCurrentNode(initialNodes));
        workflowRun.setTraceId(traceId);
        workflowRunMapper.insert(workflowRun);

        Instant startedAt = Instant.now();
        bootstrapWorkflowRuns(workflowRun.getId(), startedAt);
        initializeDispatchRuntime(request, effectiveParams, traceId, jobInstance, workflowRun, initialNodes, startedAt);

        triggerRequestMapper.updateAcceptance(request.tenantId(), request.requestId(), BatchStatusConstants.LAUNCHED, jobInstance.getId());
        return new LaunchResponse(jobInstance.getInstanceNo(), traceId);
    }

    /**
     * Launch 只负责初始化运行态和任务分发，不把 Kafka 当状态事实来源。
     */
    private void initializeDispatchRuntime(LaunchRequest request,
                                           Map<String, Object> effectiveParams,
                                           String traceId,
                                           JobInstanceEntity jobInstance,
                                           WorkflowRunEntity workflowRun,
                                           List<WorkflowDagService.DagNodeResolution> initialNodes,
                                           Instant startedAt) {
        boolean dispatchable = true;
        int partitionCount;
        String sourcePayload = buildPayloadJson(effectiveParams);
        if (initialNodes != null && !initialNodes.isEmpty()) {
            partitionCount = 0;
            for (WorkflowDagService.DagNodeResolution initialNode : initialNodes) {
                if (initialNode == null || WorkflowNodeCode.START.code().equals(initialNode.nodeCode())) {
                    continue;
                }
                partitionCount += workflowNodeDispatchService.dispatchNode(
                        jobInstance,
                        workflowRun,
                        initialNode,
                        sourcePayload,
                        traceId
                );
            }
        } else {
            SchedulePlan plan = schedulePlanBuilder.build(new SchedulePlanCommand(
                    request.tenantId(),
                    request.jobCode(),
                    request.bizDate().toString(),
                    effectiveParams
            ));
            ResourceSchedulingDecision decision = resourceScheduler.schedule(buildSchedulingRequest(plan));
            if (decision.isFailFast()) {
                throw new BizException(ResultCode.INVALID_ARGUMENT, decision.getReasonMessage());
            }
            applySchedulingDecision(plan, decision);
            List<JobPartitionEntity> partitions = partitionLifecycleService.createPartitions(
                    plan,
                    jobInstance.getId(),
                    decision.getPartitionStatus()
            );
            createTasksAndMaybeOutboxEvents(request, effectiveParams, traceId, jobInstance, plan, partitions, decision);
            partitionCount = partitions.size();
            dispatchable = decision.isDispatchable();
        }
        markLaunchRuntime(request.tenantId(), jobInstance, workflowRun, partitionCount, dispatchable, startedAt);
    }

    private void createTasksAndMaybeOutboxEvents(LaunchRequest request,
                                                 Map<String, Object> effectiveParams,
                                                 String traceId,
                                                 JobInstanceEntity jobInstance,
                                                 SchedulePlan plan,
                                                 List<JobPartitionEntity> partitions,
                                                 ResourceSchedulingDecision decision) {
        if (partitions.isEmpty()) {
            return;
        }
        for (JobPartitionEntity partition : partitions) {
            JobTaskEntity task = buildTask(request, jobInstance, plan, partition, decision, effectiveParams);
            taskExecutionService.createTask(task);
            if (decision.isDispatchable() && partitionLifecycleService.releaseForDispatch(
                    partition,
                    task,
                    com.example.batch.common.enums.PartitionStatus.CREATED.code(),
                    TaskStatus.CREATED.code()
            )) {
                taskDispatchOutboxService.writeDispatchEvent(
                        jobInstance,
                        task,
                        partition,
                        traceId,
                        request.tenantId() + ":" + task.getId()
                );
            }
        }
    }

    private JobTaskEntity buildTask(LaunchRequest request,
                                    JobInstanceEntity jobInstance,
                                    SchedulePlan plan,
                                    JobPartitionEntity partition,
                                    ResourceSchedulingDecision decision,
                                    Map<String, Object> effectiveParams) {
        JobTaskEntity task = new JobTaskEntity();
        task.setTenantId(request.tenantId());
        task.setJobInstanceId(jobInstance.getId());
        task.setJobPartitionId(partition.getId());
        task.setTaskType(resolveTaskType(plan, partition));
        task.setTaskSeq(1);
        task.setAssignedWorkerCode(resolveSelectedWorkerId(plan, partition));
        task.setTaskStatus(decision == null || decision.getTaskStatus() == null
                ? TaskStatus.READY.code()
                : decision.getTaskStatus());
        task.setTaskPayload(buildPayloadJson(effectiveParams));
        return task;
    }

    private void bootstrapWorkflowRuns(Long workflowRunId,
                                       Instant startedAt) {
        WorkflowNodeRunEntity startNodeRun = new WorkflowNodeRunEntity();
        startNodeRun.setWorkflowRunId(workflowRunId);
        startNodeRun.setNodeCode(WorkflowNodeCode.START.code());
        startNodeRun.setNodeType(com.example.batch.common.enums.WorkflowNodeType.START.code());
        startNodeRun.setRunSeq(1);
        startNodeRun.setNodeStatus(WorkflowNodeRunStatus.SUCCESS.code());
        startNodeRun.setRetryCount(0);
        startNodeRun.setDurationMs(0L);
        startNodeRun.setStartedAt(startedAt);
        startNodeRun.setFinishedAt(startedAt);
        workflowNodeRunMapper.insert(startNodeRun);
    }

    private String resolveTaskType(SchedulePlan plan, JobPartitionEntity partition) {
        if (partition != null && partition.getWorkerGroup() != null && plan != null && plan.getDefaultWorkerRoute() != null
                && plan.getDefaultWorkerRoute().getWorkerType() != null) {
            return plan.getDefaultWorkerRoute().getWorkerType();
        }
        return plan == null ? null : plan.getDefaultWorkerType();
    }

    private String resolveSelectedWorkerId(SchedulePlan plan, JobPartitionEntity partition) {
        if (partition != null && partition.getWorkerCode() != null && !partition.getWorkerCode().isBlank()) {
            return partition.getWorkerCode();
        }
        if (plan != null && plan.getDefaultWorkerRoute() != null) {
            return plan.getDefaultWorkerRoute().getWorkerId();
        }
        return null;
    }

    private ResourceSchedulingRequest buildSchedulingRequest(SchedulePlan plan) {
        ResourceSchedulingRequest request = new ResourceSchedulingRequest();
        request.setTenantId(plan.getTenantId());
        request.setJobCode(plan.getJobCode());
        request.setQueueCode(plan.getQueueCode());
        request.setWorkerGroup(plan.getWorkerGroup());
        request.setWorkerType(plan.getDefaultWorkerType());
        request.setWindowCode(plan.getWindowCode());
        request.setPriority(plan.getPriority());
        request.setRequestedPartitionCount(plan.getPartitionCount() == null ? 1 : plan.getPartitionCount());
        return request;
    }

    private void applySchedulingDecision(SchedulePlan plan, ResourceSchedulingDecision decision) {
        if (plan == null || decision == null) {
            return;
        }
        if (decision.getQueueCode() != null && !decision.getQueueCode().isBlank()) {
            plan.setQueueCode(decision.getQueueCode());
        }
        if (decision.getWorkerGroup() != null && !decision.getWorkerGroup().isBlank()) {
            plan.setWorkerGroup(decision.getWorkerGroup());
        }
        if (decision.getPriority() != null) {
            plan.setPriority(decision.getPriority());
        }
        if (decision.getRoute() != null) {
            plan.setDefaultWorkerRoute(decision.getRoute());
        }
        if (plan.getPartitions() == null) {
            return;
        }
        for (SchedulePlan.PartitionPlan partitionPlan : plan.getPartitions()) {
            partitionPlan.setPartitionStatus(decision.getPartitionStatus());
            if (decision.getRoute() != null) {
                partitionPlan.setWorkerRoute(decision.getRoute());
            }
        }
    }

    private void markLaunchRuntime(String tenantId,
                                   JobInstanceEntity jobInstance,
                                   WorkflowRunEntity workflowRun,
                                   int partitionCount,
                                   boolean dispatchable,
                                   Instant startedAt) {
        if (dispatchable) {
            int updated = jobInstanceMapper.markRunning(
                    tenantId,
                    jobInstance.getId(),
                    stateMachine.transition(jobInstance, "START").toState(),
                    partitionCount,
                    startedAt,
                    jobInstance.getVersion()
            );
            if (updated <= 0) {
                throw new BizException(ResultCode.STATE_CONFLICT, "job instance launch transition conflict");
            }
            jobInstance.setVersion((jobInstance.getVersion() == null ? 0L : jobInstance.getVersion()) + 1);
            workflowRunMapper.markRunning(
                    tenantId,
                    workflowRun.getId(),
                    stateMachine.transition(workflowRun, "START").toState(),
                    workflowRun.getCurrentNodeCode(),
                    startedAt
            );
            return;
        }
        int updated = jobInstanceMapper.markRunning(
                tenantId,
                jobInstance.getId(),
                JobInstanceStatus.WAITING.code(),
                partitionCount,
                null,
                jobInstance.getVersion()
        );
        if (updated <= 0) {
            throw new BizException(ResultCode.STATE_CONFLICT, "job instance waiting transition conflict");
        }
        jobInstance.setVersion((jobInstance.getVersion() == null ? 0L : jobInstance.getVersion()) + 1);
        workflowRunMapper.markRunning(
                tenantId,
                workflowRun.getId(),
                WorkflowRunStatus.CREATED.code(),
                workflowRun.getCurrentNodeCode(),
                null
        );
    }

    private String buildPayloadJson(Map<String, Object> params) {
        Map<String, Object> payload = params == null ? Map.of() : params;
        return JsonUtils.toJson(payload);
    }

    private String buildParamsSnapshot(JobDefinitionRecord jobDefinition,
                                       LaunchRequest request,
                                       Map<String, Object> effectiveParams,
                                       String traceId) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("jobDefinitionId", jobDefinition == null ? null : jobDefinition.getId());
        snapshot.put("jobCode", request.jobCode());
        snapshot.put("triggerType", request.triggerType() == null ? null : request.triggerType().code());
        snapshot.put("traceId", traceId);
        snapshot.put("priorityOrder", List.of("defaultParams", "requestParams", "effectiveParams"));
        snapshot.put("paramSchema", jobDefinition == null || jobDefinition.getParamSchema() == null
                ? Map.of()
                : jobDefinition.getParamSchema());
        snapshot.put("defaultParams", jobDefinition == null || jobDefinition.getDefaultParams() == null
                ? Map.of()
                : jobDefinition.getDefaultParams());
        snapshot.put("requestParams", request.params() == null ? Map.of() : request.params());
        snapshot.put("effectiveParams", effectiveParams == null ? Map.of() : effectiveParams);
        return JsonUtils.toJson(snapshot);
    }

    private String resolveInitialCurrentNode(List<WorkflowDagService.DagNodeResolution> initialNodes) {
        if (initialNodes == null || initialNodes.isEmpty()) {
            return WorkflowNodeCode.START.code();
        }
        Set<String> activeNodes = new LinkedHashSet<>();
        for (WorkflowDagService.DagNodeResolution initialNode : initialNodes) {
            if (initialNode == null || WorkflowNodeCode.START.code().equals(initialNode.nodeCode())) {
                continue;
            }
            activeNodes.add(initialNode.nodeCode());
        }
        return activeNodes.isEmpty() ? WorkflowNodeCode.START.code() : String.join(",", activeNodes);
    }

    private void validate(LaunchRequest request) {
        if (request == null) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "launch request is required");
        }
        if (request.tenantId() == null || request.tenantId().isBlank()) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "tenantId is required");
        }
        if (request.jobCode() == null || request.jobCode().isBlank()) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "jobCode is required");
        }
        if (request.bizDate() == null) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "bizDate is required");
        }
        if (request.triggerType() == null) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "triggerType is required");
        }
    }

    private Map<String, Object> mergeLaunchParams(JobDefinitionRecord jobDefinition, Map<String, Object> runtimeParams) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (jobDefinition != null && jobDefinition.getDefaultParams() != null) {
            merged.putAll(jobDefinition.getDefaultParams());
        }
        if (runtimeParams != null) {
            merged.putAll(runtimeParams);
        }
        return merged;
    }

    private Integer resolveExpectedDurationSeconds(JobDefinitionRecord jobDefinition, Map<String, Object> params) {
        Integer explicitValue = firstPositiveInt(
                params.get("expectedDurationSeconds"),
                params.get("expected_duration_seconds"),
                params.get("expectedDuration"),
                params.get("slaExpectedDurationSeconds")
        );
        if (explicitValue != null) {
            return explicitValue;
        }
        if (jobDefinition != null && jobDefinition.getTimeoutSeconds() != null && jobDefinition.getTimeoutSeconds() > 0) {
            return jobDefinition.getTimeoutSeconds();
        }
        return 0;
    }

    private String resolveBatchNo(LocalDate bizDate, Map<String, Object> params) {
        Object explicitValue = firstNonNull(params.get("batchNo"), params.get("batch_no"), params.get("batchCode"));
        if (explicitValue != null && !String.valueOf(explicitValue).isBlank()) {
            return String.valueOf(explicitValue).trim();
        }
        return bizDate == null ? null : bizDate.toString();
    }

    private String resolveOperatorId(Map<String, Object> params) {
        Object explicitValue = firstNonNull(params.get("operatorId"), params.get("operator"), params.get("userId"));
        return explicitValue == null ? null : String.valueOf(explicitValue).trim();
    }

    private boolean resolveRerunFlag(TriggerType triggerType, Map<String, Object> params) {
        if (toBoolean(params.get("rerunFlag"))) {
            return true;
        }
        String operationType = textValue(params.get("operationType"));
        return TriggerType.CATCH_UP == triggerType
                || "RERUN".equalsIgnoreCase(operationType)
                || "JOB_RERUN".equalsIgnoreCase(operationType)
                || "BATCH_RERUN".equalsIgnoreCase(operationType);
    }

    private boolean resolveRetryFlag(Map<String, Object> params) {
        if (toBoolean(params.get("retryFlag"))) {
            return true;
        }
        String operationType = textValue(params.get("operationType"));
        return "RETRY".equalsIgnoreCase(operationType)
                || "PARTITION_RETRY".equalsIgnoreCase(operationType)
                || "DLQ_REPLAY".equalsIgnoreCase(operationType);
    }

    private String resolveRerunReason(Map<String, Object> params) {
        Object explicitValue = firstNonNull(params.get("rerunReason"), params.get("reason"));
        return explicitValue == null ? null : String.valueOf(explicitValue).trim();
    }

    private Long resolveRelatedFileId(Map<String, Object> params) {
        return toPositiveLong(firstNonNull(params.get("relatedFileId"), params.get("fileId"), params.get("sourceFileId")));
    }

    private Long resolveParentInstanceId(Map<String, Object> params) {
        return toPositiveLong(firstNonNull(params.get("parentInstanceId"), params.get("targetInstanceId")));
    }

    private Instant resolveDeadlineAt(LocalDate bizDate, Map<String, Object> params) {
        Instant explicitDeadline = parseDeadlineInstant(firstNonNull(
                params.get("deadlineAt"),
                params.get("deadline"),
                params.get("slaDeadlineAt")
        ), bizDate);
        if (explicitDeadline != null) {
            return explicitDeadline;
        }
        return parseDeadlineInstant(params.get("deadlineTime"), bizDate);
    }

    private Object firstNonNull(Object... candidates) {
        for (Object candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private Integer firstPositiveInt(Object... candidates) {
        for (Object candidate : candidates) {
            Integer value = toPositiveInt(candidate);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Integer toPositiveInt(Object candidate) {
        if (candidate instanceof Number number) {
            int value = number.intValue();
            return value > 0 ? value : null;
        }
        if (candidate == null) {
            return null;
        }
        String text = String.valueOf(candidate).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            int value = Integer.parseInt(text);
            return value > 0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Long toPositiveLong(Object candidate) {
        if (candidate instanceof Number number) {
            long value = number.longValue();
            return value > 0 ? value : null;
        }
        if (candidate == null) {
            return null;
        }
        String text = String.valueOf(candidate).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            long value = Long.parseLong(text);
            return value > 0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean toBoolean(Object candidate) {
        if (candidate instanceof Boolean bool) {
            return bool;
        }
        return "true".equalsIgnoreCase(textValue(candidate))
                || "1".equals(textValue(candidate))
                || "Y".equalsIgnoreCase(textValue(candidate));
    }

    private String textValue(Object candidate) {
        if (candidate == null) {
            return null;
        }
        String text = String.valueOf(candidate).trim();
        return text.isEmpty() ? null : text;
    }

    private Instant parseDeadlineInstant(Object value, LocalDate bizDate) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.atZone(ZoneId.systemDefault()).toInstant();
        }
        if (value instanceof LocalTime localTime) {
            LocalDate effectiveDate = bizDate == null ? LocalDate.now() : bizDate;
            return effectiveDate.atTime(localTime).atZone(ZoneId.systemDefault()).toInstant();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (Exception ignored) {
        }
        try {
            return LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toInstant();
        } catch (Exception ignored) {
        }
        try {
            LocalDate effectiveDate = bizDate == null ? LocalDate.now() : bizDate;
            return effectiveDate.atTime(LocalTime.parse(text)).atZone(ZoneId.systemDefault()).toInstant();
        } catch (Exception ignored) {
        }
        return null;
    }
}
