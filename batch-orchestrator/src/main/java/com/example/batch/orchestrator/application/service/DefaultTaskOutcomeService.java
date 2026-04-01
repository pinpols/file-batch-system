package com.example.batch.orchestrator.application.service;

import com.example.batch.common.exception.BizException;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.enums.WorkflowNodeCode;
import com.example.batch.common.enums.WorkflowNodeRunStatus;
import com.example.batch.common.enums.WorkflowNodeType;
import com.example.batch.common.enums.WorkflowRunStatus;
import com.example.batch.common.enums.JobInstanceStatus;
import com.example.batch.common.persistence.entity.WorkflowRunEntity;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.domain.command.TaskOutcomeCommand;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobStepInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import com.example.batch.orchestrator.domain.query.JobPartitionQuery;
import com.example.batch.orchestrator.domain.query.JobTaskQuery;
import com.example.batch.orchestrator.domain.statemachine.StateMachine;
import com.example.batch.orchestrator.mapper.FinishTaskParam;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.JobStepInstanceMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import com.example.batch.orchestrator.mapper.MarkPartitionStatusParam;
import com.example.batch.orchestrator.mapper.UpdateStepProgressParam;
import com.example.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import com.example.batch.orchestrator.mapper.WorkflowRunMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * worker 回报（report）后的“状态推进中枢”。
 *
 * <p>在本系统中，worker 不直接修改 orchestrator 的运行态表，而是通过 HTTP {@code /internal/tasks/{taskId}/report}
 * 回报执行结果；orchestrator 在这里统一完成：
 * <ul>
 *   <li>写入 task 的终态（SUCCESS/FAILED）</li>
 *   <li>根据失败决定是否进入重试（写 retry_schedule，并把 partition/task/step 标记为 RETRYING）</li>
 *   <li>推进 partition/job_instance/workflow_run 的状态机（含 DAG 节点切换与下一节点派发）</li>
 *   <li>更新 step 镜像 {@code job_step_instance}（用于审计/可视化口径一致）</li>
 * </ul>
 *
 * <p>重要约束：
 * <ul>
 *   <li>只接受 RUNNING 状态的 task 回报，避免重复 report 导致状态回跳。</li>
 *   <li>并发冲突靠 DB 乐观锁/条件更新兜底（更新行数为 0 → STATE_CONFLICT）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DefaultTaskOutcomeService implements TaskOutcomeService {

    private final JobTaskMapper jobTaskMapper;
    private final JobPartitionMapper jobPartitionMapper;
    private final JobInstanceMapper jobInstanceMapper;
    private final JobStepInstanceMapper jobStepInstanceMapper;
    private final WorkflowRunMapper workflowRunMapper;
    private final WorkflowNodeRunMapper workflowNodeRunMapper;
    private final RetryGovernanceService retryGovernanceService;
    private final StateMachine<Object> stateMachine;
    private final WorkflowDagService workflowDagService;
    private final ObjectProvider<WorkflowNodeDispatchService> workflowNodeDispatchServiceProvider;

    @Override
    @Transactional
    public WorkflowNodeRunEntity recordNodeRunReady(Long workflowRunId, String nodeCode, String nodeType) {
        WorkflowNodeRunEntity entity = new WorkflowNodeRunEntity();
        entity.setWorkflowRunId(workflowRunId);
        entity.setNodeCode(nodeCode);
        entity.setNodeType(nodeType);
        entity.setRunSeq(nextRunSeq(workflowRunId, nodeCode));
        entity.setNodeStatus(WorkflowNodeRunStatus.READY.code());
        entity.setRetryCount(0);
        entity.setDurationMs(0L);
        workflowNodeRunMapper.insert(entity);
        return entity;
    }

    @Override
    @Transactional
    public WorkflowNodeRunEntity recordNodeRunStart(Long workflowRunId, String nodeCode, String nodeType, Instant startedAt) {
        WorkflowNodeRunEntity entity = new WorkflowNodeRunEntity();
        entity.setWorkflowRunId(workflowRunId);
        entity.setNodeCode(nodeCode);
        entity.setNodeType(nodeType);
        entity.setRunSeq(nextRunSeq(workflowRunId, nodeCode));
        entity.setNodeStatus(WorkflowNodeRunStatus.RUNNING.code());
        entity.setRetryCount(0);
        entity.setStartedAt(startedAt);
        entity.setDurationMs(0L);
        workflowNodeRunMapper.insert(entity);
        return entity;
    }

    @Override
    @Transactional
    public WorkflowNodeRunEntity recordNodeRunFinish(NodeRunFinishCommand command) {
        WorkflowNodeRunEntity current = workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(
                command.workflowRunId(), command.nodeCode());
        if (current == null) {
            current = recordNodeRunStart(
                    command.workflowRunId(), command.nodeCode(), command.nodeType(), command.startedAt());
        }
        long duration = command.startedAt() == null || command.finishedAt() == null
                ? 0L
                : Duration.between(command.startedAt(), command.finishedAt()).toMillis();
        workflowNodeRunMapper.updateStatus(
                current.getId(),
                command.success() ? WorkflowNodeRunStatus.SUCCESS.code() : WorkflowNodeRunStatus.FAILED.code(),
                command.errorCode(),
                command.errorMessage(),
                duration,
                command.finishedAt()
        );
        return workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(command.workflowRunId(), command.nodeCode());
    }

    @Override
    @Transactional
    public JobTaskEntity applyTaskOutcome(TaskOutcomeCommand command) {
        // 入口语义：worker report → orchestrator 在单事务内完成“任务完成 +（可选）重试入队 + 状态机推进”。
        if (command == null) {
            return null;
        }
        JobTaskEntity task = jobTaskMapper.selectById(command.tenantId(), command.taskId());
        if (task == null) {
            return null;
        }
        // 只处理 RUNNING → terminal 的一次性回报；重复回报直接返回当前状态，保证幂等。
        if (!TaskStatus.RUNNING.code().equals(task.getTaskStatus())) {
            return task;
        }
        Instant finishedAt = finishedAtOrNow();
        JobPartitionEntity partition = jobPartitionMapper.selectById(command.tenantId(), task.getJobPartitionId());
        JobInstanceEntity jobInstance = jobInstanceMapper.selectById(command.tenantId(), task.getJobInstanceId());
        // 失败时是否进入重试：由治理层统一决策（NONE/预算耗尽 → dead-letter；否则写 retry_schedule）。
        boolean retryScheduled = !command.success()
                && partition != null
                && jobInstance != null
                && retryGovernanceService.scheduleRetryIfNecessary(task, partition, jobInstance, command.errorCode(), command.errorMessage());
        int updated = jobTaskMapper.finishTask(FinishTaskParam.builder()
                .tenantId(command.tenantId()).id(command.taskId())
                .taskStatus(command.success() ? TaskStatus.SUCCESS.code() : TaskStatus.FAILED.code())
                .expectedStatus(TaskStatus.RUNNING.code())
                .resultSummary(command.resultSummary())
                .errorCode(command.errorCode()).errorMessage(command.errorMessage())
                .expectedVersion(task.getVersion()).build());
        if (updated <= 0) {
            throw new BizException(ResultCode.STATE_CONFLICT,
                    "task already finished by concurrent update: taskId=" + command.taskId());
        }
        // partition 是 job_instance 的“并行执行单元”，task outcome 会汇聚到 partition 状态上。
        if (partition != null) {
            if (command.success()) {
                jobPartitionMapper.markStatus(MarkPartitionStatusParam.builder()
                        .tenantId(command.tenantId()).id(partition.getId())
                        .partitionStatus(PartitionStatus.SUCCESS.code()).runningStatus(PartitionStatus.RUNNING.code())
                        .terminalStatus1(PartitionStatus.SUCCESS.code()).terminalStatus2(PartitionStatus.FAILED.code())
                        .terminalStatus3(PartitionStatus.CANCELLED.code()).terminalStatus4(PartitionStatus.TERMINATED.code())
                        .expectedVersion(partition.getVersion()).build());
            } else if (retryScheduled) {
                // 进入 RETRYING：partition 先标记为 RETRYING，实际重排队由 retry scheduler → outbox 完成。
                jobPartitionMapper.markRetrying(
                        command.tenantId(),
                        partition.getId(),
                        Optional.ofNullable(partition.getRetryCount()).orElse(0) + 1,
                        PartitionStatus.RETRYING.code(),
                        partition.getVersion()
                );
            } else {
                jobPartitionMapper.markStatus(MarkPartitionStatusParam.builder()
                        .tenantId(command.tenantId()).id(partition.getId())
                        .partitionStatus(PartitionStatus.FAILED.code()).runningStatus(PartitionStatus.RUNNING.code())
                        .terminalStatus1(PartitionStatus.SUCCESS.code()).terminalStatus2(PartitionStatus.FAILED.code())
                        .terminalStatus3(PartitionStatus.CANCELLED.code()).terminalStatus4(PartitionStatus.TERMINATED.code())
                        .expectedVersion(partition.getVersion()).build());
            }
            jobPartitionMapper.updateOutputSummary(
                    command.tenantId(),
                    partition.getId(),
                    buildOutputSummary(command, task)
            );
        }
        // step 镜像用于“按 step 维度”看执行状态/重试次数，与 task/partition 状态保持一致口径。
        updateStepInstanceProgress(command, task, retryScheduled, finishedAt);
        if (jobInstance != null) {
            // job_instance/workflow_run 的推进依赖“当前节点 partitions 是否全部结束”，结束后可能切换 DAG 节点并派发 nextNodes。
            List<JobPartitionEntity> partitions = jobPartitionMapper.selectByQuery(new JobPartitionQuery(
                    command.tenantId(),
                    task.getJobInstanceId(),
                    null,
                    null
            ));
            long successCount = partitions.stream().filter(p -> PartitionStatus.SUCCESS.code().equals(p.getPartitionStatus())).count();
            long failedCount = partitions.stream().filter(p -> PartitionStatus.FAILED.code().equals(p.getPartitionStatus())).count();
            long finishedPartitionCount = successCount + failedCount;
            boolean allPartitionsFinished = !partitions.isEmpty() && finishedPartitionCount == partitions.size();
            WorkflowRunEntity workflowRun = workflowRunMapper.selectByRelatedJobInstanceId(command.tenantId(), jobInstance.getId());
            String currentNodeCode = resolveCurrentNodeCode(task, workflowRun);
            List<JobTaskEntity> tasks = jobTaskMapper.selectByQuery(new JobTaskQuery(
                    command.tenantId(),
                    task.getJobInstanceId(),
                    null,
                    null,
                    null
            ));
            NodePartitionProgress nodeProgress = resolveNodePartitionProgress(partitions, tasks, currentNodeCode, workflowRun);
            Set<String> activeNodes = workflowRun == null
                    ? new LinkedHashSet<>()
                    : parseActiveNodes(workflowRun.getCurrentNodeCode());
            List<WorkflowDagService.DagNodeResolution> nextNodes = nodeProgress.allFinished() && workflowRun != null
                    ? workflowDagService.resolveNextNodes(
                            workflowRun.getWorkflowDefinitionId(),
                            currentNodeCode,
                            nodeProgress.failedCount() == 0,
                            task.getTaskPayload()
                    )
                    : List.of();
            if (nodeProgress.allFinished() && workflowRun != null) {
                activeNodes.remove(currentNodeCode);
                recordNodeRunFinish(NodeRunFinishCommand.of(
                        new NodeRunKey(
                                workflowRun.getId(),
                                currentNodeCode,
                                resolveCurrentNodeType(task)
                        ),
                        new NodeRunOutcome(
                                nodeProgress.failedCount() == 0,
                                command.errorCode(),
                                command.errorMessage(),
                                resolveNodeStartedAt(workflowRun.getId(), currentNodeCode, workflowRun.getStartedAt(), finishedAt),
                                finishedAt
                        )
                ));
                for (WorkflowDagService.DagNodeResolution nextNode : nextNodes) {
                    if (nextNode == null) {
                        continue;
                    }
                    if (WorkflowNodeCode.END.code().equals(nextNode.nodeCode())) {
                        if (workflowDagService.isNodeReadyForDispatch(
                                workflowRun.getId(),
                                workflowRun.getWorkflowDefinitionId(),
                                nextNode.nodeCode(),
                                task.getTaskPayload()
                        )) {
                            recordNodeRunStart(workflowRun.getId(), nextNode.nodeCode(), nextNode.nodeType(), finishedAt);
                            recordNodeRunFinish(NodeRunFinishCommand.of(
                                    new NodeRunKey(
                                            workflowRun.getId(),
                                            nextNode.nodeCode(),
                                            nextNode.nodeType()
                                    ),
                                    new NodeRunOutcome(
                                            nodeProgress.failedCount() == 0,
                                            command.errorCode(),
                                            command.errorMessage(),
                                            finishedAt,
                                            finishedAt
                                    )
                            ));
                        }
                        continue;
                    }
                    workflowNodeDispatchServiceProvider.getObject().dispatchNode(
                            jobInstance,
                            workflowRun,
                            nextNode,
                            task.getTaskPayload(),
                            jobInstance.getTraceId()
                    );
                    if (isActiveNode(workflowRun.getId(), nextNode.nodeCode())) {
                        activeNodes.add(nextNode.nodeCode());
                    }
                }
            }
            boolean dagContinues = workflowRun != null && !activeNodes.isEmpty();
            String instanceEvent = resolveInstanceEvent(successCount, failedCount, allPartitionsFinished, dagContinues);
            String instanceStatus = stateMachine.transition(jobInstance, instanceEvent).toState();
            int progressUpdated = jobInstanceMapper.updateProgress(
                    command.tenantId(),
                    jobInstance.getId(),
                    instanceStatus,
                    (int) successCount,
                    (int) failedCount,
                    buildJobInstanceResultSummary(jobInstance, partitions, command),
                    allPartitionsFinished && !dagContinues ? finishedAt : null,
                    jobInstance.getVersion()
            );
            if (progressUpdated <= 0) {
                throw new BizException(ResultCode.STATE_CONFLICT, "job instance progress conflict");
            }
            jobInstance.setVersion(Optional.ofNullable(jobInstance.getVersion()).orElse(0L) + 1);
            // 若本作业由 DAG 中 JOB 节点子作业拉起，需回写父侧信号
            if (allPartitionsFinished && !dagContinues && isTerminalJobInstanceStatus(instanceStatus)) {
                signalParentVirtualTask(jobInstance, instanceStatus, command, finishedAt);
            }
            if (workflowRun != null) {
                String workflowEvent = resolveWorkflowEvent(failedCount, allPartitionsFinished, dagContinues);
                String workflowStatus = stateMachine.transition(workflowRun, workflowEvent).toState();
                workflowRunMapper.updateStatus(
                        command.tenantId(),
                        workflowRun.getId(),
                        workflowStatus,
                        resolveWorkflowCurrentNode(activeNodes, workflowStatus, currentNodeCode),
                        allPartitionsFinished && !dagContinues ? finishedAt : null
                );
            }
        }
        return jobTaskMapper.selectById(command.tenantId(), command.taskId());
    }

    private void updateStepInstanceProgress(TaskOutcomeCommand command,
                                            JobTaskEntity task,
                                            boolean retryScheduled,
                                            Instant finishedAt) {
        if (command == null || task == null) {
            return;
        }
        JobStepInstanceEntity stepInstance = jobStepInstanceMapper.selectByJobTaskId(command.tenantId(), task.getId());
        if (stepInstance == null) {
            return;
        }
        String nextStatus = retryScheduled
                ? "RETRYING"
                : command.success() ? TaskStatus.SUCCESS.code() : TaskStatus.FAILED.code();
        int currentRetryCount = Optional.ofNullable(stepInstance.getRetryCount()).orElse(0);
        int nextRetryCount = retryScheduled ? currentRetryCount + 1 : currentRetryCount;
        int updated = jobStepInstanceMapper.updateProgress(UpdateStepProgressParam.builder()
                .tenantId(command.tenantId()).id(stepInstance.getId())
                .stepStatus(nextStatus).retryCount(nextRetryCount)
                .relatedFileId(resolveRelatedFileId(task, command))
                .resultSummary(buildOutputSummary(command, task))
                .errorCode(command.errorCode()).errorMessage(command.errorMessage())
                .finishedAt(retryScheduled ? null : finishedAt)
                .expectedVersion(stepInstance.getVersion()).build());
        if (updated <= 0) {
            throw new BizException(ResultCode.STATE_CONFLICT, "job step instance progress conflict");
        }
    }

    private String buildOutputSummary(TaskOutcomeCommand command, JobTaskEntity task) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("taskId", command == null ? null : command.taskId());
        summary.put("tenantId", command == null ? null : command.tenantId());
        summary.put("success", command != null && command.success());
        summary.put("resultSummary", command == null ? null : command.resultSummary());
        summary.put("errorCode", command == null ? null : command.errorCode());
        summary.put("errorMessage", command == null ? null : command.errorMessage());
        summary.put("taskPayload", task == null ? null : task.getTaskPayload());
        summary.put("recordedAt", Instant.now().toString());
        return JsonUtils.toJson(summary);
    }

    private String buildJobInstanceResultSummary(JobInstanceEntity jobInstance,
                                                 List<JobPartitionEntity> partitions,
                                                 TaskOutcomeCommand command) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("jobInstanceId", jobInstance == null ? null : jobInstance.getId());
        summary.put("lastTaskId", command == null ? null : command.taskId());
        summary.put("successPartitions", partitions == null ? 0L : partitions.stream()
                .filter(partition -> PartitionStatus.SUCCESS.code().equals(partition.getPartitionStatus()))
                .count());
        summary.put("failedPartitions", partitions == null ? 0L : partitions.stream()
                .filter(partition -> PartitionStatus.FAILED.code().equals(partition.getPartitionStatus()))
                .count());
        summary.put("lastErrorCode", command == null ? null : command.errorCode());
        summary.put("lastErrorMessage", command == null ? null : command.errorMessage());
        summary.put("updatedAt", Instant.now().toString());
        return JsonUtils.toJson(summary);
    }

    // ── JOB-node child completion signal ────────────────────────────────────────

    /**
     * When a child job (launched by a JOB node) reaches a terminal state, apply the
     * outcome to the virtual task that was created in the parent job so that the standard
     * partition-based DAG advancement logic takes over.
     */
    private void signalParentVirtualTask(JobInstanceEntity childJobInstance,
                                         String childInstanceStatus,
                                         TaskOutcomeCommand childCommand,
                                         Instant finishedAt) {
        Long parentVirtualTaskId = extractParentVirtualTaskId(childJobInstance.getParamsSnapshot());
        if (parentVirtualTaskId == null) {
            return;
        }
        boolean nodeSuccess = JobInstanceStatus.SUCCESS.code().equals(childInstanceStatus);
        applyTaskOutcome(new TaskOutcomeCommand(
                childJobInstance.getTenantId(),
                parentVirtualTaskId,
                nodeSuccess,
                JsonUtils.toJson(Map.of("childInstanceStatus", childInstanceStatus)),
                nodeSuccess ? null : childCommand.errorCode(),
                nodeSuccess ? null : childCommand.errorMessage()
        ));
    }

    @SuppressWarnings("unchecked")
    private Long extractParentVirtualTaskId(String paramsSnapshot) {
        if (paramsSnapshot == null || paramsSnapshot.isBlank()) {
            return null;
        }
        try {
            Object parsed = JsonUtils.fromJson(paramsSnapshot, Object.class);
            if (!(parsed instanceof Map<?, ?> snapshotMap)) {
                return null;
            }
            Object effectiveParams = ((Map<String, Object>) snapshotMap).get("effectiveParams");
            if (!(effectiveParams instanceof Map<?, ?> effectiveMap)) {
                return null;
            }
            Object value = ((Map<String, Object>) effectiveMap).get("_parentVirtualTaskId");
            return toPositiveLong(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isTerminalJobInstanceStatus(String status) {
        return JobInstanceStatus.SUCCESS.code().equals(status)
                || JobInstanceStatus.FAILED.code().equals(status)
                || JobInstanceStatus.PARTIAL_FAILED.code().equals(status)
                || JobInstanceStatus.CANCELLED.code().equals(status)
                || JobInstanceStatus.TERMINATED.code().equals(status);
    }

    private String resolveCurrentNodeCode(JobTaskEntity task, WorkflowRunEntity workflowRun) {
        String nodeCode = payloadStringValue(task == null ? null : task.getTaskPayload(), "workflowNodeCode");
        if (nodeCode != null && !nodeCode.isBlank()) {
            return nodeCode;
        }
        Set<String> activeNodes = workflowRun == null ? Set.of() : parseActiveNodes(workflowRun.getCurrentNodeCode());
        if (!activeNodes.isEmpty()) {
            return activeNodes.iterator().next();
        }
        return WorkflowNodeCode.START.code();
    }

    private String resolveCurrentNodeType(JobTaskEntity task) {
        String nodeType = payloadStringValue(task == null ? null : task.getTaskPayload(), "workflowNodeType");
        return nodeType == null || nodeType.isBlank() ? WorkflowNodeType.TASK.code() : nodeType;
    }

    private NodePartitionProgress resolveNodePartitionProgress(List<JobPartitionEntity> partitions,
                                                              List<JobTaskEntity> tasks,
                                                              String nodeCode,
                                                              WorkflowRunEntity workflowRun) {
        if (nodeCode == null || nodeCode.isBlank()) {
            return new NodePartitionProgress(0, 0, 0);
        }
        Map<Long, JobPartitionEntity> partitionsById = new LinkedHashMap<>();
        for (JobPartitionEntity partition : partitions) {
            if (partition == null || partition.getId() == null) {
                continue;
            }
            partitionsById.put(partition.getId(), partition);
        }
        Set<Long> nodePartitionIds = new LinkedHashSet<>();
        for (JobTaskEntity task : tasks) {
            if (task == null || task.getJobPartitionId() == null) {
                continue;
            }
            String taskNodeCode = resolveTaskNodeCode(task, workflowRun, nodeCode);
            if (nodeCode.equals(taskNodeCode)) {
                nodePartitionIds.add(task.getJobPartitionId());
            }
        }
        long nodeSuccessCount = 0L;
        long nodeFailedCount = 0L;
        for (Long partitionId : nodePartitionIds) {
            JobPartitionEntity partition = partitionsById.get(partitionId);
            if (partition == null) {
                continue;
            }
            if (PartitionStatus.SUCCESS.code().equals(partition.getPartitionStatus())) {
                nodeSuccessCount++;
            } else if (PartitionStatus.FAILED.code().equals(partition.getPartitionStatus())) {
                nodeFailedCount++;
            }
        }
        return new NodePartitionProgress(nodePartitionIds.size(), nodeSuccessCount, nodeFailedCount);
    }

    private String resolveTaskNodeCode(JobTaskEntity task,
                                       WorkflowRunEntity workflowRun,
                                       String fallbackNodeCode) {
        String taskNodeCode = payloadStringValue(task == null ? null : task.getTaskPayload(), "workflowNodeCode");
        if (taskNodeCode != null && !taskNodeCode.isBlank()) {
            return taskNodeCode;
        }
        if (workflowRun != null && workflowRun.getCurrentNodeCode() != null && !workflowRun.getCurrentNodeCode().isBlank()) {
            Set<String> activeNodes = parseActiveNodes(workflowRun.getCurrentNodeCode());
            if (activeNodes.size() == 1) {
                return activeNodes.iterator().next();
            }
        }
        return fallbackNodeCode;
    }

    private Set<String> parseActiveNodes(String currentNodeCode) {
        Set<String> activeNodes = new LinkedHashSet<>();
        if (currentNodeCode == null || currentNodeCode.isBlank()) {
            return activeNodes;
        }
        for (String nodeCode : currentNodeCode.split(",")) {
            if (nodeCode == null || nodeCode.isBlank()) {
                continue;
            }
            activeNodes.add(nodeCode.trim());
        }
        return activeNodes;
    }

    private boolean isActiveNode(Long workflowRunId, String nodeCode) {
        WorkflowNodeRunEntity latestNodeRun = workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(workflowRunId, nodeCode);
        if (latestNodeRun == null) {
            return false;
        }
        return WorkflowNodeRunStatus.READY.code().equals(latestNodeRun.getNodeStatus())
                || WorkflowNodeRunStatus.RUNNING.code().equals(latestNodeRun.getNodeStatus());
    }

    private Instant resolveNodeStartedAt(Long workflowRunId,
                                         String nodeCode,
                                         Instant workflowStartedAt,
                                         Instant finishedAt) {
        WorkflowNodeRunEntity latestNodeRun = workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(workflowRunId, nodeCode);
        if (latestNodeRun != null && latestNodeRun.getStartedAt() != null) {
            return latestNodeRun.getStartedAt();
        }
        if (workflowStartedAt != null) {
            return workflowStartedAt;
        }
        return finishedAt;
    }

    private String resolveInstanceEvent(long successCount, long failedCount, boolean allPartitionsFinished, boolean dagContinues) {
        if (!allPartitionsFinished) {
            return JobInstanceStatus.RUNNING.code();
        }
        if (dagContinues) {
            return JobInstanceStatus.RUNNING.code();
        }
        if (failedCount > 0 && successCount > 0) {
            return JobInstanceStatus.PARTIAL_FAILED.code();
        }
        if (failedCount > 0) {
            return JobInstanceStatus.FAILED.code();
        }
        return JobInstanceStatus.SUCCESS.code();
    }

    /**
     * workflow_run 只允许进入 workflow 语义状态，不复用 job_instance 的 PARTIAL_FAILED 等口径。
     */
    private String resolveWorkflowEvent(long failedCount, boolean allPartitionsFinished, boolean dagContinues) {
        if (!allPartitionsFinished) {
            return WorkflowRunStatus.RUNNING.code();
        }
        if (dagContinues) {
            return WorkflowRunStatus.RUNNING.code();
        }
        return failedCount > 0 ? WorkflowRunStatus.FAILED.code() : WorkflowRunStatus.SUCCESS.code();
    }

    private String resolveWorkflowCurrentNode(Set<String> activeNodes,
                                              String workflowStatus,
                                              String fallbackNodeCode) {
        if (activeNodes != null && !activeNodes.isEmpty()) {
            return String.join(",", activeNodes);
        }
        if (isWorkflowTerminal(workflowStatus)) {
            return WorkflowNodeCode.END.code();
        }
        return fallbackNodeCode;
    }

    private boolean isWorkflowTerminal(String workflowStatus) {
        return WorkflowRunStatus.SUCCESS.code().equals(workflowStatus)
                || WorkflowRunStatus.FAILED.code().equals(workflowStatus)
                || WorkflowRunStatus.TERMINATED.code().equals(workflowStatus);
    }

    private Instant finishedAtOrNow() {
        return Instant.now();
    }

    private Long resolveRelatedFileId(JobTaskEntity task, TaskOutcomeCommand command) {
        return firstPositiveLong(
                resolveRelatedFileId(task),
                payloadLongValue(command == null ? null : command.resultSummary(), "relatedFileId"),
                payloadLongValue(command == null ? null : command.resultSummary(), "fileId")
        );
    }

    private Long resolveRelatedFileId(JobTaskEntity task) {
        return firstPositiveLong(
                payloadLongValue(task == null ? null : task.getTaskPayload(), "relatedFileId"),
                payloadLongValue(task == null ? null : task.getTaskPayload(), "fileId"),
                payloadLongValue(task == null ? null : task.getTaskPayload(), "sourceFileId")
        );
    }

    @SuppressWarnings("unchecked")
    private String payloadStringValue(String payloadJson, String fieldName) {
        if (payloadJson == null || payloadJson.isBlank() || fieldName == null || fieldName.isBlank()) {
            return null;
        }
        try {
            Object payloadObject = JsonUtils.fromJson(payloadJson, Object.class);
            if (payloadObject instanceof Map<?, ?> payloadMap) {
                Object value = ((Map<String, Object>) payloadMap).get(fieldName);
                return value == null ? null : String.valueOf(value);
            }
        } catch (IllegalArgumentException exception) {
            return null;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Long payloadLongValue(String payloadJson, String fieldName) {
        if (payloadJson == null || payloadJson.isBlank() || fieldName == null || fieldName.isBlank()) {
            return null;
        }
        try {
            Object payloadObject = JsonUtils.fromJson(payloadJson, Object.class);
            if (payloadObject instanceof Map<?, ?> payloadMap) {
                Object value = ((Map<String, Object>) payloadMap).get(fieldName);
                return toPositiveLong(value);
            }
        } catch (IllegalArgumentException exception) {
            return null;
        }
        return null;
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

    private Long firstPositiveLong(Long... candidates) {
        for (Long candidate : candidates) {
            if (candidate != null && candidate > 0) {
                return candidate;
            }
        }
        return null;
    }

    private int nextRunSeq(Long workflowRunId, String nodeCode) {
        WorkflowNodeRunEntity current = workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(workflowRunId, nodeCode);
        return current == null || current.getRunSeq() == null ? 1 : current.getRunSeq() + 1;
    }

    private record NodePartitionProgress(int partitionCount, long successCount, long failedCount) {

        private boolean allFinished() {
            return partitionCount > 0 && successCount + failedCount == partitionCount;
        }
    }
}
