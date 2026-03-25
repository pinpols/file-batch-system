package com.example.batch.orchestrator.service;

import com.example.batch.common.constants.BatchStatusConstants;
import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.JobInstanceStatus;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.enums.WorkflowNodeCode;
import com.example.batch.common.enums.WorkflowNodeRunStatus;
import com.example.batch.common.enums.WorkflowNodeType;
import com.example.batch.common.enums.WorkflowRunStatus;
import com.example.batch.common.persistence.entity.WorkflowRunEntity;
import com.example.batch.common.utils.IdGenerator;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.application.service.PartitionDispatchService;
import com.example.batch.orchestrator.application.service.WorkflowDagService;
import com.example.batch.orchestrator.domain.entity.JobDefinitionRecord;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.TriggerRequestMapper;
import com.example.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import com.example.batch.orchestrator.mapper.WorkflowRunMapper;
import com.example.batch.orchestrator.service.LaunchValidationService.LaunchLoadResult;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the job launch lifecycle in two committed transactions:
 * <ul>
 *   <li>T1 ({@link #prepareJobInstance}): creates {@code job_instance} + {@code workflow_run} +
 *       bootstraps the START node run, then commits. Invoked via self-proxy to honour
 *       {@code @Transactional} through Spring AOP.</li>
 *   <li>T2 ({@link PartitionDispatchService#dispatch}): creates partitions, tasks and outbox
 *       events, then marks the instance as RUNNING, in its own transaction on the
 *       {@link PartitionDispatchService} bean.</li>
 * </ul>
 * The split ensures that the high-contention partition/task tables are locked only during T2,
 * not for the full duration of instance creation.
 */
@Service
public class DefaultLaunchService implements LaunchService {

    private final LaunchValidationService launchValidationService;
    private final PartitionDispatchService partitionDispatchService;
    private final TriggerRequestMapper triggerRequestMapper;
    private final JobInstanceMapper jobInstanceMapper;
    private final WorkflowRunMapper workflowRunMapper;
    private final WorkflowNodeRunMapper workflowNodeRunMapper;
    private final WorkflowDagService workflowDagService;

    /**
     * Self-reference injected lazily to let Spring AOP intercept {@link #prepareJobInstance}
     * and apply the {@code @Transactional} advice for T1.
     */
    @Lazy
    @Autowired
    private DefaultLaunchService self;

    public DefaultLaunchService(LaunchValidationService launchValidationService,
                                 PartitionDispatchService partitionDispatchService,
                                 TriggerRequestMapper triggerRequestMapper,
                                 JobInstanceMapper jobInstanceMapper,
                                 WorkflowRunMapper workflowRunMapper,
                                 WorkflowNodeRunMapper workflowNodeRunMapper,
                                 WorkflowDagService workflowDagService) {
        this.launchValidationService = launchValidationService;
        this.partitionDispatchService = partitionDispatchService;
        this.triggerRequestMapper = triggerRequestMapper;
        this.jobInstanceMapper = jobInstanceMapper;
        this.workflowRunMapper = workflowRunMapper;
        this.workflowNodeRunMapper = workflowNodeRunMapper;
        this.workflowDagService = workflowDagService;
    }

    @Override
    public LaunchResponse launch(LaunchRequest request) {
        LaunchLoadResult loaded = launchValidationService.load(request);
        if (loaded.existingInstance() != null) {
            triggerRequestMapper.updateAcceptance(request.tenantId(), request.requestId(),
                    BatchStatusConstants.DUPLICATE, loaded.existingInstance().getId());
            return new LaunchResponse(loaded.existingInstance().getInstanceNo(),
                    loaded.existingInstance().getTraceId());
        }

        String traceId = request.traceId() == null || request.traceId().isBlank()
                ? IdGenerator.newTraceId() : request.traceId();
        Map<String, Object> effectiveParams = mergeLaunchParams(loaded.jobDefinition(), request.params());

        // T1: job instance + workflow run — commits before T2 reads them
        PreparedLaunch prepared = self.prepareJobInstance(request, loaded, effectiveParams, traceId);

        // T2: partitions + tasks + outbox events + mark RUNNING — separate transaction
        partitionDispatchService.dispatch(request, effectiveParams, traceId,
                prepared.jobInstance(), prepared.workflowRun(),
                prepared.initialNodes(), prepared.startedAt());

        triggerRequestMapper.updateAcceptance(request.tenantId(), request.requestId(),
                BatchStatusConstants.LAUNCHED, prepared.jobInstance().getId());
        return new LaunchResponse(prepared.jobInstance().getInstanceNo(), traceId);
    }

    /**
     * T1: inserts {@code job_instance}, {@code workflow_run}, and the bootstrap START
     * {@code workflow_node_run} in one atomic transaction, then returns the prepared state
     * for T2 to consume.
     */
    @Transactional
    public PreparedLaunch prepareJobInstance(LaunchRequest request,
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
        jobInstance.setBatchNo(resolveBatchNo(request.bizDate(), effectiveParams));
        jobInstance.setOperatorId(resolveOperatorId(effectiveParams));
        jobInstance.setRerunFlag(resolveRerunFlag(request.triggerType(), effectiveParams));
        jobInstance.setRetryFlag(resolveRetryFlag(effectiveParams));
        jobInstance.setRerunReason(resolveRerunReason(effectiveParams));
        jobInstance.setRelatedFileId(resolveRelatedFileId(effectiveParams));
        jobInstance.setParentInstanceId(resolveParentInstanceId(effectiveParams));
        jobInstance.setQueueCode(loaded.jobDefinition().queueCode());
        jobInstance.setWorkerGroup(loaded.jobDefinition().workerGroup());
        jobInstance.setPriority(loaded.jobDefinition().priority() == null ? 5 : loaded.jobDefinition().priority());
        jobInstance.setDedupKey(loaded.triggerRequest().getDedupKey());
        jobInstance.setVersion(0L);
        jobInstance.setExpectedPartitionCount(0);
        jobInstance.setSuccessPartitionCount(0);
        jobInstance.setFailedPartitionCount(0);
        jobInstance.setTraceId(traceId);
        jobInstance.setParamsSnapshot(buildParamsSnapshot(loaded.jobDefinition(), request, effectiveParams, traceId));
        jobInstance.setResultSummary(null);
        jobInstance.setDeadlineAt(resolveDeadlineAt(request.bizDate(), effectiveParams));
        jobInstance.setExpectedDurationSeconds(resolveExpectedDurationSeconds(loaded.jobDefinition(), effectiveParams));
        jobInstance.setSlaAlertedAt(null);
        jobInstanceMapper.insert(jobInstance);

        List<WorkflowDagService.DagNodeResolution> initialNodes = workflowDagService.resolveInitialNodes(
                loaded.workflowDefinition().id(), buildPayloadJson(effectiveParams));

        WorkflowRunEntity workflowRun = new WorkflowRunEntity();
        workflowRun.setTenantId(request.tenantId());
        workflowRun.setWorkflowDefinitionId(loaded.workflowDefinition().id());
        workflowRun.setRelatedJobInstanceId(jobInstance.getId());
        workflowRun.setBizDate(request.bizDate());
        workflowRun.setRunStatus(WorkflowRunStatus.CREATED.code());
        workflowRun.setCurrentNodeCode(resolveInitialCurrentNode(initialNodes));
        workflowRun.setTraceId(traceId);
        workflowRunMapper.insert(workflowRun);

        Instant startedAt = Instant.now();
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
        workflowNodeRunMapper.insert(startNodeRun);

        return new PreparedLaunch(jobInstance, workflowRun, initialNodes, startedAt);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private String resolveInitialCurrentNode(List<WorkflowDagService.DagNodeResolution> initialNodes) {
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

    private Map<String, Object> mergeLaunchParams(JobDefinitionRecord jobDefinition,
                                                   Map<String, Object> runtimeParams) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (jobDefinition != null && jobDefinition.defaultParams() != null) {
            merged.putAll(jobDefinition.defaultParams());
        }
        if (runtimeParams != null) {
            merged.putAll(runtimeParams);
        }
        return merged;
    }

    private String buildPayloadJson(Map<String, Object> params) {
        return JsonUtils.toJson(params == null ? Map.of() : params);
    }

    private String buildParamsSnapshot(JobDefinitionRecord jobDefinition,
                                        LaunchRequest request,
                                        Map<String, Object> effectiveParams,
                                        String traceId) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("jobDefinitionId", jobDefinition == null ? null : jobDefinition.id());
        snapshot.put("jobCode", request.jobCode());
        snapshot.put("triggerType", request.triggerType() == null ? null : request.triggerType().code());
        snapshot.put("traceId", traceId);
        snapshot.put("priorityOrder", List.of("defaultParams", "requestParams", "effectiveParams"));
        snapshot.put("paramSchema", jobDefinition == null || jobDefinition.paramSchema() == null
                ? Map.of() : jobDefinition.paramSchema());
        snapshot.put("defaultParams", jobDefinition == null || jobDefinition.defaultParams() == null
                ? Map.of() : jobDefinition.defaultParams());
        snapshot.put("requestParams", request.params() == null ? Map.of() : request.params());
        snapshot.put("effectiveParams", effectiveParams == null ? Map.of() : effectiveParams);
        return JsonUtils.toJson(snapshot);
    }

    private Integer resolveExpectedDurationSeconds(JobDefinitionRecord jobDefinition,
                                                    Map<String, Object> params) {
        Integer explicitValue = firstPositiveInt(
                params.get("expectedDurationSeconds"),
                params.get("expected_duration_seconds"),
                params.get("expectedDuration"),
                params.get("slaExpectedDurationSeconds")
        );
        if (explicitValue != null) {
            return explicitValue;
        }
        if (jobDefinition != null && jobDefinition.timeoutSeconds() != null && jobDefinition.timeoutSeconds() > 0) {
            return jobDefinition.timeoutSeconds();
        }
        return 0;
    }

    private String resolveBatchNo(LocalDate bizDate, Map<String, Object> params) {
        Object v = firstNonNull(params.get("batchNo"), params.get("batch_no"), params.get("batchCode"));
        if (v != null && !String.valueOf(v).isBlank()) {
            return String.valueOf(v).trim();
        }
        return bizDate == null ? null : bizDate.toString();
    }

    private String resolveOperatorId(Map<String, Object> params) {
        Object v = firstNonNull(params.get("operatorId"), params.get("operator"), params.get("userId"));
        return v == null ? null : String.valueOf(v).trim();
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
        Object v = firstNonNull(params.get("rerunReason"), params.get("reason"));
        return v == null ? null : String.valueOf(v).trim();
    }

    private Long resolveRelatedFileId(Map<String, Object> params) {
        return toPositiveLong(firstNonNull(params.get("relatedFileId"), params.get("fileId"),
                params.get("sourceFileId")));
    }

    private Long resolveParentInstanceId(Map<String, Object> params) {
        return toPositiveLong(firstNonNull(params.get("parentInstanceId"), params.get("targetInstanceId")));
    }

    private Instant resolveDeadlineAt(LocalDate bizDate, Map<String, Object> params) {
        Instant explicit = parseDeadlineInstant(
                firstNonNull(params.get("deadlineAt"), params.get("deadline"), params.get("slaDeadlineAt")),
                bizDate);
        return explicit != null ? explicit : parseDeadlineInstant(params.get("deadlineTime"), bizDate);
    }

    private Object firstNonNull(Object... candidates) {
        for (Object c : candidates) {
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    private Integer firstPositiveInt(Object... candidates) {
        for (Object c : candidates) {
            Integer v = toPositiveInt(c);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private Integer toPositiveInt(Object candidate) {
        if (candidate instanceof Number n) {
            int v = n.intValue();
            return v > 0 ? v : null;
        }
        if (candidate == null) {
            return null;
        }
        String text = String.valueOf(candidate).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            int v = Integer.parseInt(text);
            return v > 0 ? v : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Long toPositiveLong(Object candidate) {
        if (candidate instanceof Number n) {
            long v = n.longValue();
            return v > 0 ? v : null;
        }
        if (candidate == null) {
            return null;
        }
        String text = String.valueOf(candidate).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            long v = Long.parseLong(text);
            return v > 0 ? v : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean toBoolean(Object candidate) {
        if (candidate instanceof Boolean b) {
            return b;
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
        if (value instanceof LocalDateTime ldt) {
            return ldt.atZone(ZoneId.systemDefault()).toInstant();
        }
        if (value instanceof LocalTime lt) {
            LocalDate d = bizDate == null ? LocalDate.now() : bizDate;
            return d.atTime(lt).atZone(ZoneId.systemDefault()).toInstant();
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
            LocalDate d = bizDate == null ? LocalDate.now() : bizDate;
            return d.atTime(LocalTime.parse(text)).atZone(ZoneId.systemDefault()).toInstant();
        } catch (Exception ignored) {
        }
        return null;
    }

    private record PreparedLaunch(
            JobInstanceEntity jobInstance,
            WorkflowRunEntity workflowRun,
            List<WorkflowDagService.DagNodeResolution> initialNodes,
            Instant startedAt) {}
}
