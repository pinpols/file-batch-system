package com.example.batch.orchestrator.service;

import com.example.batch.common.constants.BatchStatusConstants;
import com.example.batch.common.context.RunModeSupport;
import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.JobInstanceStatus;
import com.example.batch.common.enums.RunMode;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.enums.WorkflowNodeCode;
import com.example.batch.common.enums.WorkflowNodeRunStatus;
import com.example.batch.common.enums.WorkflowNodeType;
import com.example.batch.common.enums.WorkflowRunStatus;
import com.example.batch.common.persistence.entity.WorkflowRunEntity;
import com.example.batch.common.utils.IdGenerator;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.common.logging.AuditLogConstants;
import com.example.batch.orchestrator.application.service.PartitionDispatchService;
import com.example.batch.orchestrator.application.service.WorkflowDagService;
import com.example.batch.orchestrator.domain.entity.BatchDayInstanceRecord;
import com.example.batch.orchestrator.domain.entity.BusinessCalendarRecord;
import com.example.batch.orchestrator.domain.entity.JobDefinitionRecord;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.TriggerRequestMapper;
import com.example.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import com.example.batch.orchestrator.mapper.WorkflowRunMapper;
import com.example.batch.orchestrator.mapper.JobExecutionLogMapper;
import com.example.batch.orchestrator.repository.BatchDayInstanceRepository;
import com.example.batch.orchestrator.repository.BusinessCalendarRepository;
import com.example.batch.orchestrator.service.LaunchValidationService.LaunchLoadResult;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.context.annotation.Lazy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 批任务启动（Launch）的核心入口：把一次触发请求落地为可调度的运行态，并驱动后续分片/任务派发。
 *
 * <p>这里把 launch 拆成两段<strong>独立提交</strong>的事务（T1/T2），目的是降低锁竞争与提升可重试性：
 * <ul>
 *   <li><strong>T1（准备态落库）</strong>：只创建 {@code job_instance}/{@code workflow_run} 以及 START 节点的运行态，
 *       快速提交，作为后续调度/派发的“事实源”。</li>
 *   <li><strong>T2（运行态构建与派发）</strong>：创建 partition/task、写 outbox，并推进 instance/workflow 状态。
 *       高竞争表只在 T2 短事务里触碰，避免长事务持锁。</li>
 * </ul>
 *
 * <p>注意：{@link #prepareJobInstance} 必须通过 self-proxy 调用，才能让 Spring AOP 的 {@code @Transactional} 生效。
 */
@Service
@RequiredArgsConstructor
public class DefaultLaunchService implements LaunchService {

    private final LaunchValidationService launchValidationService;
    private final PartitionDispatchService partitionDispatchService;
    private final TriggerRequestMapper triggerRequestMapper;
    private final JobInstanceMapper jobInstanceMapper;
    private final WorkflowRunMapper workflowRunMapper;
    private final WorkflowNodeRunMapper workflowNodeRunMapper;
    private final WorkflowDagService workflowDagService;
    private final BusinessCalendarRepository businessCalendarRepository;
    private final BatchDayInstanceRepository batchDayInstanceRepository;
    private final JobExecutionLogMapper jobExecutionLogMapper;

    /**
     * 通过延迟注入的 self 触发 AOP 拦截，确保 {@link #prepareJobInstance} 的 {@code @Transactional} 在同类调用中仍生效。
     */
    @Lazy
    @Autowired
    private DefaultLaunchService self;

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
        LaunchRequest routedRequest = routeLateArrivalIfNeeded(request, loaded);
        Map<String, Object> effectiveParams = mergeLaunchParams(
                loaded.jobDefinition(),
                routedRequest.triggerType(),
                routedRequest.params()
        );

        // T1：先把 instance/workflow 落库并提交，避免 T2 执行期间持有更长时间锁。
        PreparedLaunch prepared;
        try {
            prepared = self.prepareJobInstance(routedRequest, loaded, effectiveParams, traceId);
        } catch (DuplicateKeyException exception) {
            return resolveConcurrentDuplicate(request, loaded, exception);
        } catch (DataIntegrityViolationException exception) {
            return resolveConcurrentDuplicate(request, loaded, exception);
        } catch (RuntimeException exception) {
            // PG 唯一约束等可能被包装为 TransactionSystemException / UncategorizedDataAccess 等，需沿 cause 识别 23505
            if (hasSqlStateInChain(exception, "23505")) {
                return resolveConcurrentDuplicate(request, loaded, exception);
            }
            throw exception;
        }

        // T2：构建分片/任务/outbox，并推进运行态；该事务可在失败后独立重试。
        partitionDispatchService.dispatch(PartitionDispatchService.DispatchContext.of(
                request,
                effectiveParams,
                traceId,
                prepared.jobInstance(),
                prepared.workflowRun(),
                prepared.initialNodes(),
                prepared.startedAt()
        ));

        triggerRequestMapper.updateAcceptance(request.tenantId(), request.requestId(),
                BatchStatusConstants.LAUNCHED, prepared.jobInstance().getId());
        return new LaunchResponse(prepared.jobInstance().getInstanceNo(), traceId);
    }

    /**
     * T1 事务：创建 {@code job_instance}/{@code workflow_run}，并补齐 START 节点运行态。
     *
     * <p>该事务只做“准备态落库”，不触碰高竞争的 task/partition/outbox 表，从而缩短锁持有时间。
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
        Instant batchDaySlaDeadlineAt = resolveBatchDaySlaDeadlineAt(
                request.tenantId(),
                loaded.jobDefinition().calendarCode(),
                request.bizDate()
        );
        Instant createdAt = Instant.now();
        jobInstance.setDeadlineAt(resolveDeadlineAt(
                createdAt,
                request.bizDate(),
                loaded.jobDefinition(),
                effectiveParams,
                batchDaySlaDeadlineAt
        ));
        jobInstance.setExpectedDurationSeconds(resolveExpectedDurationSeconds(loaded.jobDefinition(), effectiveParams));
        jobInstance.setSlaAlertedAt(null);
        jobInstanceMapper.insert(jobInstance);
        upsertBatchDayInstance(request, loaded.jobDefinition(), effectiveParams, batchDaySlaDeadlineAt);

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

    // ── 辅助方法 ─────────────────────────────────────────────────────────────────

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
                                                  TriggerType triggerType,
                                                  Map<String, Object> runtimeParams) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (jobDefinition != null && jobDefinition.defaultParams() != null) {
            merged.putAll(jobDefinition.defaultParams());
        }
        if (runtimeParams != null) {
            merged.putAll(runtimeParams);
        }
        return RunModeSupport.copyWithDefault(merged, resolveRunMode(triggerType, merged));
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

    private LaunchResponse resolveConcurrentDuplicate(LaunchRequest request,
                                                      LaunchLoadResult loaded,
                                                      RuntimeException exception) {
        JobInstanceEntity existingInstance = jobInstanceMapper.selectByTenantAndDedupKey(
                request.tenantId(), loaded.triggerRequest().getDedupKey());
        if (existingInstance == null) {
            throw exception;
        }
        triggerRequestMapper.updateAcceptance(request.tenantId(), request.requestId(),
                BatchStatusConstants.DUPLICATE, existingInstance.getId());
        return new LaunchResponse(existingInstance.getInstanceNo(), existingInstance.getTraceId());
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

    private RunMode resolveRunMode(TriggerType triggerType, Map<String, Object> params) {
        RunMode explicit = RunModeSupport.resolve(params);
        if (explicit != null) {
            return explicit;
        }
        String operationType = textValue(params.get("operationType"));
        if ("COMPENSATE".equalsIgnoreCase(operationType) || "COMPENSATION".equalsIgnoreCase(operationType)) {
            return RunMode.COMPENSATE;
        }
        if ("RECOVER".equalsIgnoreCase(operationType) || "FAILOVER_RECOVER".equalsIgnoreCase(operationType)) {
            return RunMode.RECOVER;
        }
        if (resolveRetryFlag(params)) {
            return RunMode.RETRY;
        }
        if (resolveRerunFlag(triggerType, params)) {
            return RunMode.RERUN;
        }
        return RunMode.NORMAL;
    }

    private String resolveRerunReason(Map<String, Object> params) {
        Object v = firstNonNull(params.get("rerunReason"), params.get("reason"));
        return v == null ? null : String.valueOf(v).trim();
    }

    private void upsertBatchDayInstance(LaunchRequest request,
                                        JobDefinitionRecord jobDefinition,
                                        Map<String, Object> effectiveParams,
                                        Instant batchDaySlaDeadlineAt) {
        if (request == null || request.bizDate() == null || jobDefinition == null) {
            return;
        }
        String calendarCode = textValue(jobDefinition.calendarCode());
        if (!StringUtils.hasText(calendarCode)) {
            return;
        }
        Instant now = Instant.now();
        BatchDayInstanceRecord existing = batchDayInstanceRepository.findFirstByTenantIdAndCalendarCodeAndBizDate(
                request.tenantId(), calendarCode, request.bizDate());
        Instant cutoffAt = resolveBatchDayCutoffAt(request.tenantId(), calendarCode, request.bizDate());
        String operatorId = resolveOperatorId(effectiveParams);
        String auditOperatorId = StringUtils.hasText(operatorId) ? operatorId : AuditLogConstants.OPERATOR_ID_SYSTEM;
        String auditOperatorType = StringUtils.hasText(operatorId) ? AuditLogConstants.OPERATOR_TYPE_REQUEST : AuditLogConstants.OPERATOR_TYPE_SYSTEM;
        if (existing == null) {
            boolean catchUpLaunch = isCatchUpLaunch(request);
            boolean lateAccepted = isLateAccepted(effectiveParams);
            boolean pastCutoff = cutoffAt != null && !now.isBefore(cutoffAt);
            String dayStatus = (catchUpLaunch || lateAccepted)
                    ? "IN_FLIGHT"
                    : (pastCutoff ? "CUTOFF" : "OPEN");
            String reasonCode = (catchUpLaunch || lateAccepted)
                    ? (lateAccepted ? "LATE_ACCEPTED" : "CATCH_UP_LAUNCHED")
                    : (pastCutoff ? "CUTOFF_REACHED_ON_CREATE" : "BATCH_DAY_OPENED");
            batchDayInstanceRepository.save(new BatchDayInstanceRecord(
                    null,
                    request.tenantId(),
                    calendarCode,
                    request.bizDate(),
                    dayStatus,
                    now,
                    cutoffAt,
                    null,
                    batchDaySlaDeadlineAt,
                    lateAccepted ? 1 : 0,
                    catchUpLaunch ? 1 : 0,
                    now,
                    now
            ));
            appendBatchDayAuditLog(
                    request.tenantId(),
                    request.traceId(),
                    null,
                    dayStatus,
                    calendarCode,
                    request.bizDate(),
                    reasonCode,
                    auditOperatorId,
                    auditOperatorType,
                    lateAccepted ? 1 : 0,
                    catchUpLaunch ? 1 : 0,
                    cutoffAt
            );
            return;
        }
        boolean lateAccepted = isLateAccepted(effectiveParams);
        boolean catchUpLaunch = isCatchUpLaunch(request);
        boolean pastCutoff = cutoffAt != null && !now.isBefore(cutoffAt);
        boolean shouldMoveToCutoff = "OPEN".equalsIgnoreCase(existing.dayStatus()) && pastCutoff;
        boolean shouldReopen = shouldReopenBatchDay(existing.dayStatus()) || lateAccepted || catchUpLaunch;
        BatchDayInstanceRecord updated = existing;
        boolean changed = false;
        String fromDayStatus = existing.dayStatus();
        String toDayStatus = existing.dayStatus();
        String reasonCode = null;
        if (updated.slaDeadlineAt() == null && batchDaySlaDeadlineAt != null) {
            updated = updated.withSlaDeadlineAt(batchDaySlaDeadlineAt, now);
            changed = true;
        }
        if (updated.cutoffAt() == null && cutoffAt != null) {
            updated = updated.withCutoffAt(cutoffAt, now);
            changed = true;
        }
        if (shouldMoveToCutoff && !lateAccepted && !catchUpLaunch) {
            updated = updated.withDayStatus("CUTOFF", now);
            changed = true;
            reasonCode = "CUTOFF_REACHED";
        }
        if (lateAccepted) {
            updated = updated.withLateCount(safeIncrement(updated.lateCount()), now);
            changed = true;
            reasonCode = "LATE_ACCEPTED";
        }
        if (catchUpLaunch) {
            updated = updated.withCatchupCount(safeIncrement(updated.catchupCount()), now);
            changed = true;
            reasonCode = "CATCH_UP_LAUNCHED";
        }
        if (shouldReopen) {
            updated = updated.withReopened(now);
            changed = true;
            reasonCode = lateAccepted ? "LATE_ACCEPTED_REOPEN" : (catchUpLaunch ? "CATCH_UP_REOPEN" : "BATCH_DAY_REOPENED");
        }
        if (!changed) {
            return;
        }
        toDayStatus = updated.dayStatus();
        batchDayInstanceRepository.save(updated);
        appendBatchDayAuditLog(
                request.tenantId(),
                request.traceId(),
                fromDayStatus,
                toDayStatus,
                calendarCode,
                request.bizDate(),
                reasonCode == null ? "BATCH_DAY_UPDATED" : reasonCode,
                auditOperatorId,
                auditOperatorType,
                updated.lateCount(),
                updated.catchupCount(),
                cutoffAt
        );
    }

    private Instant resolveBatchDayCutoffAt(String tenantId, String calendarCode, LocalDate bizDate) {
        BusinessCalendarRecord calendar = businessCalendarRepository.findFirstByTenantIdAndCalendarCodeAndEnabled(
                tenantId, calendarCode, true);
        if (calendar == null) {
            return null;
        }
        LocalTime cutoffTime = calendar.cutoffTime() == null ? LocalTime.of(6, 0) : calendar.cutoffTime();
        ZoneId zoneId = StringUtils.hasText(calendar.timezone())
                ? ZoneId.of(calendar.timezone())
                : ZoneId.systemDefault();
        return bizDate.plusDays(1).atTime(cutoffTime).atZone(zoneId).toInstant();
    }

    private void appendBatchDayAuditLog(String tenantId,
                                         String traceId,
                                         String fromDayStatus,
                                         String toDayStatus,
                                         String calendarCode,
                                         LocalDate bizDate,
                                         String reasonCode,
                                         String operatorId,
                                         String operatorType,
                                         Integer lateCount,
                                         Integer catchupCount,
                                         Instant cutoffAt) {
        JobExecutionLogEntity logEntity = new JobExecutionLogEntity();
        logEntity.setTenantId(tenantId);
        logEntity.setJobInstanceId(null);
        logEntity.setJobPartitionId(null);
        logEntity.setLogLevel("INFO");
        logEntity.setLogType(AuditLogConstants.LOG_TYPE_AUDIT);
        logEntity.setTraceId(traceId);
        logEntity.setMessage("BATCH_DAY_INSTANCE_STATE_CHANGED");
        logEntity.setDetailRef(AuditLogConstants.DETAIL_REF_BATCH_DAY_INSTANCE);
        logEntity.setExtraJson(JsonUtils.toJson(new LinkedHashMap<>() {{
            put("calendarCode", calendarCode);
            put("bizDate", bizDate == null ? null : bizDate.toString());
            put("fromDayStatus", fromDayStatus);
            put("toDayStatus", toDayStatus);
            put("reasonCode", reasonCode);
            put("operatorId", operatorId);
            put("operatorType", operatorType);
            put("lateCount", lateCount);
            put("catchupCount", catchupCount);
            put("cutoffAt", cutoffAt == null ? null : cutoffAt.toString());
        }}));
        jobExecutionLogMapper.insert(logEntity);
    }

    private Instant resolveBatchDaySlaDeadlineAt(String tenantId, String calendarCode, LocalDate bizDate) {
        BusinessCalendarRecord calendar = businessCalendarRepository.findFirstByTenantIdAndCalendarCodeAndEnabled(
                tenantId, calendarCode, true);
        if (calendar == null || calendar.slaOffsetMin() == null || calendar.slaOffsetMin() <= 0) {
            return null;
        }
        LocalTime cutoffTime = calendar.cutoffTime() == null ? LocalTime.of(6, 0) : calendar.cutoffTime();
        ZoneId zoneId = StringUtils.hasText(calendar.timezone())
                ? ZoneId.of(calendar.timezone())
                : ZoneId.systemDefault();
        Instant cutoffAt = bizDate.plusDays(1).atTime(cutoffTime).atZone(zoneId).toInstant();
        return cutoffAt.plusSeconds(calendar.slaOffsetMin() * 60L);
    }

    private boolean shouldReopenBatchDay(String dayStatus) {
        return "FAILED".equalsIgnoreCase(dayStatus) || "SETTLED".equalsIgnoreCase(dayStatus);
    }

    private boolean isLateAccepted(Map<String, Object> params) {
        if (params == null) {
            return false;
        }
        Object lateArrival = params.get("lateArrival");
        Object arrivalStatus = params.get("arrivalStatus");
        return toBoolean(lateArrival) && "LATE_ACCEPTED".equalsIgnoreCase(textValue(arrivalStatus));
    }

    private boolean isCatchUpLaunch(LaunchRequest request) {
        return request != null && TriggerType.CATCH_UP == request.triggerType();
    }

    private LaunchRequest routeLateArrivalIfNeeded(LaunchRequest request, LaunchLoadResult loaded) {
        if (request == null || request.triggerType() != TriggerType.EVENT || loaded == null
                || loaded.jobDefinition() == null || request.bizDate() == null) {
            return request;
        }
        String calendarCode = textValue(loaded.jobDefinition().calendarCode());
        if (!StringUtils.hasText(calendarCode)) {
            return request;
        }
        BatchDayInstanceRecord batchDay = batchDayInstanceRepository.findFirstByTenantIdAndCalendarCodeAndBizDate(
                request.tenantId(),
                calendarCode,
                request.bizDate()
        );
        if (batchDay == null || batchDay.dayStatus() == null) {
            return request;
        }
        String dayStatus = batchDay.dayStatus();
        // OPEN 仍可能已到 cutoff 时间（例如定时任务延迟切换），此时也应按 CUTOFF 逻辑评估 late arrival
        boolean pastCutoff = isPastBatchDayCutoff(batchDay, calendarCode);
        if ("IN_FLIGHT".equalsIgnoreCase(dayStatus) || ("OPEN".equalsIgnoreCase(dayStatus) && !pastCutoff)) {
            return request;
        }

        boolean treatAsCutoff = "CUTOFF".equalsIgnoreCase(dayStatus) || pastCutoff;
        boolean lateAccepted = treatAsCutoff && isWithinLateArrivalTolerance(batchDay, calendarCode);
        Map<String, Object> routedParams = new LinkedHashMap<>();
        if (request.params() != null) {
            routedParams.putAll(request.params());
        }
        routedParams.put("lateArrival", true);
        routedParams.put("arrivalStatus", lateAccepted ? "LATE_ACCEPTED" : "LATE_REJECTED");
        routedParams.put("batchDayStatus", dayStatus);
        if (batchDay.cutoffAt() != null) {
            routedParams.put("batchDayCutoffAt", batchDay.cutoffAt().toString());
        }
        if (lateAccepted) {
            routedParams.put("lateArrivalToleranceMin", resolveLateArrivalToleranceMin(request.tenantId(), calendarCode));
        } else {
            routedParams.put("catchUpReason", "LATE_ARRIVAL_OR_CLOSED_BATCH_DAY");
            loaded.triggerRequest().setTriggerType(TriggerType.CATCH_UP.code());
            triggerRequestMapper.updateTriggerType(request.tenantId(), request.requestId(), TriggerType.CATCH_UP.code());
        }
        return new LaunchRequest(
                request.tenantId(),
                request.jobCode(),
                request.bizDate(),
                lateAccepted ? TriggerType.EVENT : TriggerType.CATCH_UP,
                request.requestId(),
                request.traceId(),
                routedParams
        );
    }

    private boolean isPastBatchDayCutoff(BatchDayInstanceRecord batchDay, String calendarCode) {
        if (batchDay == null || batchDay.bizDate() == null || batchDay.tenantId() == null) {
            return false;
        }
        Instant cutoffAt = batchDay.cutoffAt();
        if (cutoffAt == null) {
            cutoffAt = resolveBatchDayCutoffAt(batchDay.tenantId(), calendarCode, batchDay.bizDate());
        }
        return cutoffAt != null && !Instant.now().isBefore(cutoffAt);
    }

    private boolean isWithinLateArrivalTolerance(BatchDayInstanceRecord batchDay, String calendarCode) {
        if (batchDay == null || !StringUtils.hasText(calendarCode)) {
            return false;
        }
        Instant cutoffAt = batchDay.cutoffAt();
        if (cutoffAt == null) {
            cutoffAt = resolveBatchDayCutoffAt(batchDay.tenantId(), calendarCode, batchDay.bizDate());
        }
        if (cutoffAt == null) {
            return false;
        }
        Instant cutoffCloseAt = cutoffAt.plusSeconds(
                Math.max(0, resolveLateArrivalToleranceMin(batchDay.tenantId(), calendarCode)) * 60L
        );
        return !Instant.now().isAfter(cutoffCloseAt);
    }

    private Integer resolveLateArrivalToleranceMin(String tenantId, String calendarCode) {
        BusinessCalendarRecord calendar = businessCalendarRepository.findFirstByTenantIdAndCalendarCodeAndEnabled(
                tenantId,
                calendarCode,
                true
        );
        if (calendar == null || calendar.lateArrivalToleranceMin() == null || calendar.lateArrivalToleranceMin() < 0) {
            return 0;
        }
        return calendar.lateArrivalToleranceMin();
    }

    private Integer safeIncrement(Integer value) {
        return value == null ? 1 : value + 1;
    }

    private Long resolveRelatedFileId(Map<String, Object> params) {
        return toPositiveLong(firstNonNull(params.get("relatedFileId"), params.get("fileId"),
                params.get("sourceFileId")));
    }

    private Long resolveParentInstanceId(Map<String, Object> params) {
        return toPositiveLong(firstNonNull(params.get("parentInstanceId"), params.get("targetInstanceId")));
    }

    private Instant resolveDeadlineAt(Instant createdAt,
                                      LocalDate bizDate,
                                      JobDefinitionRecord jobDefinition,
                                      Map<String, Object> params,
                                      Instant batchDaySlaDeadlineAt) {
        Instant explicit = parseDeadlineInstant(
                firstNonNull(params.get("deadlineAt"), params.get("deadline"), params.get("slaDeadlineAt")),
                bizDate);
        Instant deadlineTime = parseDeadlineInstant(params.get("deadlineTime"), bizDate);
        Instant jobDeadlineAt = resolveJobDeadlineAt(createdAt, jobDefinition);
        return earliest(explicit, deadlineTime, jobDeadlineAt, batchDaySlaDeadlineAt);
    }

    private Instant resolveJobDeadlineAt(Instant createdAt, JobDefinitionRecord jobDefinition) {
        if (createdAt == null || jobDefinition == null || jobDefinition.timeoutSeconds() == null
                || jobDefinition.timeoutSeconds() <= 0) {
            return null;
        }
        return createdAt.plusSeconds(jobDefinition.timeoutSeconds());
    }

    private Instant earliest(Instant... candidates) {
        Instant result = null;
        for (Instant candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            if (result == null || candidate.isBefore(result)) {
                result = candidate;
            }
        }
        return result;
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
