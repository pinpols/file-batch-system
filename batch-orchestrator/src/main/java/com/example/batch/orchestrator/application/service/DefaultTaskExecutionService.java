package com.example.batch.orchestrator.application.service;

import com.example.batch.common.enums.RetryScheduleStatus;
import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowRunEntity;
import com.example.batch.orchestrator.domain.command.TaskOutcomeCommand;
import com.example.batch.orchestrator.config.PartitionLeaseProperties;
import com.example.batch.orchestrator.domain.statemachine.StateMachine;
import com.example.batch.orchestrator.domain.query.JobExecutionLogQuery;
import com.example.batch.orchestrator.domain.query.JobTaskQuery;
import com.example.batch.orchestrator.mapper.JobExecutionLogMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import com.example.batch.orchestrator.mapper.WorkflowRunMapper;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.WorkerRegistryRecord;
import com.example.batch.common.enums.JobInstanceStatus;
import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.enums.WorkflowNodeCode;
import com.example.batch.common.enums.WorkflowNodeRunStatus;
import com.example.batch.common.enums.WorkflowNodeType;
import com.example.batch.common.enums.WorkflowRunStatus;
import com.example.batch.orchestrator.repository.WorkerRegistryRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultTaskExecutionService implements TaskExecutionService {

    private final JobTaskMapper jobTaskMapper;
    private final JobExecutionLogMapper jobExecutionLogMapper;
    private final JobPartitionMapper jobPartitionMapper;
    private final JobInstanceMapper jobInstanceMapper;
    private final WorkflowRunMapper workflowRunMapper;
    private final WorkflowNodeRunMapper workflowNodeRunMapper;
    private final PartitionLeaseProperties partitionLeaseProperties;
    private final RetryGovernanceService retryGovernanceService;
    private final StateMachine<Object> stateMachine;
    private final WorkerRegistryRepository workerRegistryRepository;
    private final WorkflowDagService workflowDagService;
    private final WorkflowNodeDispatchService workflowNodeDispatchService;

    public DefaultTaskExecutionService(JobTaskMapper jobTaskMapper,
                                       JobExecutionLogMapper jobExecutionLogMapper,
                                       JobPartitionMapper jobPartitionMapper,
                                       JobInstanceMapper jobInstanceMapper,
                                       WorkflowRunMapper workflowRunMapper,
                                       WorkflowNodeRunMapper workflowNodeRunMapper,
                                       PartitionLeaseProperties partitionLeaseProperties,
                                       RetryGovernanceService retryGovernanceService,
                                       StateMachine<Object> stateMachine,
                                       WorkerRegistryRepository workerRegistryRepository,
                                       WorkflowDagService workflowDagService,
                                       WorkflowNodeDispatchService workflowNodeDispatchService) {
        this.jobTaskMapper = jobTaskMapper;
        this.jobExecutionLogMapper = jobExecutionLogMapper;
        this.jobPartitionMapper = jobPartitionMapper;
        this.jobInstanceMapper = jobInstanceMapper;
        this.workflowRunMapper = workflowRunMapper;
        this.workflowNodeRunMapper = workflowNodeRunMapper;
        this.partitionLeaseProperties = partitionLeaseProperties;
        this.retryGovernanceService = retryGovernanceService;
        this.stateMachine = stateMachine;
        this.workerRegistryRepository = workerRegistryRepository;
        this.workflowDagService = workflowDagService;
        this.workflowNodeDispatchService = workflowNodeDispatchService;
    }

    @Override
    @Transactional
    public JobTaskEntity createTask(JobTaskEntity task) {
        jobTaskMapper.insert(task);
        return task;
    }

    @Override
    @Transactional
    public JobTaskEntity assignWorker(String tenantId, Long taskId, String workerCode) {
        JobTaskEntity current = jobTaskMapper.selectById(tenantId, taskId);
        if (current == null) {
            return null;
        }
        if (!isWorkerClaimable(tenantId, workerCode, current)) {
            return current;
        }
        int updated = jobTaskMapper.assignWorker(tenantId, taskId, workerCode, TaskStatus.RUNNING.code());
        if (updated <= 0) {
            return jobTaskMapper.selectById(tenantId, taskId);
        }
        if (current.getJobPartitionId() != null) {
            int claimed = jobPartitionMapper.claimPartition(
                    tenantId,
                    current.getJobPartitionId(),
                    workerCode,
                    Instant.now().plusSeconds(partitionLeaseProperties.getExpireSeconds()),
                    PartitionStatus.READY.code(),
                    PartitionStatus.RUNNING.code()
            );
            if (claimed <= 0) {
                throw new IllegalStateException("partition claim failed for taskId=" + taskId);
            }
        }
        return jobTaskMapper.selectById(tenantId, taskId);
    }

    @Override
    @Transactional
    public boolean renewTaskLease(String tenantId, Long taskId, String workerCode) {
        JobTaskEntity current = jobTaskMapper.selectById(tenantId, taskId);
        if (current == null || current.getJobPartitionId() == null) {
            return false;
        }
        if (!TaskStatus.RUNNING.code().equals(current.getTaskStatus())) {
            return false;
        }
        if (workerCode == null || !workerCode.equals(current.getAssignedWorkerCode())) {
            return false;
        }
        return jobPartitionMapper.renewLease(
                tenantId,
                current.getJobPartitionId(),
                workerCode,
                Instant.now().plusSeconds(partitionLeaseProperties.getExpireSeconds())
        ) > 0;
    }

    @Override
    @Transactional
    public JobTaskEntity updateTaskStatus(String tenantId, Long taskId, String taskStatus, String errorCode, String errorMessage) {
        JobTaskEntity current = jobTaskMapper.selectById(tenantId, taskId);
        if (current == null) {
            return null;
        }
        jobTaskMapper.updateStatus(tenantId, taskId, taskStatus, errorCode, errorMessage);
        return jobTaskMapper.selectById(tenantId, taskId);
    }

    @Override
    @Transactional
    public JobExecutionLogEntity appendLog(JobExecutionLogEntity log) {
        jobExecutionLogMapper.insert(log);
        return log;
    }

    @Override
    public List<JobExecutionLogEntity> listLogs(String tenantId, Long jobInstanceId, Long jobPartitionId) {
        return jobExecutionLogMapper.selectByQuery(new JobExecutionLogQuery(tenantId, jobInstanceId, jobPartitionId, null, null));
    }

    @Override
    @Transactional
    public JobTaskEntity markRunning(String tenantId, Long taskId, Instant startedAt) {
        JobTaskEntity current = jobTaskMapper.selectById(tenantId, taskId);
        if (current == null) {
            return null;
        }
        current.setStartedAt(startedAt);
        current.setTaskStatus(TaskStatus.RUNNING.code());
        jobTaskMapper.updateStatus(tenantId, taskId, TaskStatus.RUNNING.code(), null, null);
        return jobTaskMapper.selectById(tenantId, taskId);
    }

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
    public WorkflowNodeRunEntity recordNodeRunFinish(Long workflowRunId,
                                                     String nodeCode,
                                                     String nodeType,
                                                     boolean success,
                                                     String errorCode,
                                                     String errorMessage,
                                                     Instant startedAt,
                                                     Instant finishedAt) {
        WorkflowNodeRunEntity current = workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(workflowRunId, nodeCode);
        if (current == null) {
            current = recordNodeRunStart(workflowRunId, nodeCode, nodeType, startedAt);
        }
        long duration = startedAt == null || finishedAt == null ? 0L : Duration.between(startedAt, finishedAt).toMillis();
        workflowNodeRunMapper.updateStatus(
                current.getId(),
                success ? WorkflowNodeRunStatus.SUCCESS.code() : WorkflowNodeRunStatus.FAILED.code(),
                errorCode,
                errorMessage,
                duration,
                finishedAt
        );
        return workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(workflowRunId, nodeCode);
    }

    @Override
    @Transactional
    public JobTaskEntity applyTaskOutcome(TaskOutcomeCommand command) {
        if (command == null) {
            return null;
        }
        JobTaskEntity task = jobTaskMapper.selectById(command.tenantId(), command.taskId());
        if (task == null) {
            return null;
        }
        if (!TaskStatus.RUNNING.code().equals(task.getTaskStatus())) {
            return task;
        }
        Instant finishedAt = finishedAtOrNow();
        JobPartitionEntity partition = jobPartitionMapper.selectById(command.tenantId(), task.getJobPartitionId());
        JobInstanceEntity jobInstance = jobInstanceMapper.selectById(command.tenantId(), task.getJobInstanceId());
        boolean retryScheduled = !command.success()
                && partition != null
                && jobInstance != null
                && retryGovernanceService.scheduleRetryIfNecessary(task, partition, jobInstance, command.errorCode(), command.errorMessage());
        jobTaskMapper.updateStatus(command.tenantId(), command.taskId(),
                command.success() ? TaskStatus.SUCCESS.code() : TaskStatus.FAILED.code(),
                command.errorCode(), command.errorMessage());
        if (partition != null) {
            if (command.success()) {
                jobPartitionMapper.markStatus(command.tenantId(), partition.getId(), PartitionStatus.SUCCESS.code());
            } else if (retryScheduled) {
                jobPartitionMapper.markRetrying(
                        command.tenantId(),
                        partition.getId(),
                        (partition.getRetryCount() == null ? 0 : partition.getRetryCount()) + 1
                );
            } else {
                jobPartitionMapper.markStatus(command.tenantId(), partition.getId(), PartitionStatus.FAILED.code());
            }
        }
        if (jobInstance != null) {
            List<JobPartitionEntity> partitions = jobPartitionMapper.selectByQuery(new com.example.batch.orchestrator.domain.query.JobPartitionQuery(
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
                recordNodeRunFinish(
                        workflowRun.getId(),
                        currentNodeCode,
                        resolveCurrentNodeType(task),
                        nodeProgress.failedCount() == 0,
                        command.errorCode(),
                        command.errorMessage(),
                        resolveNodeStartedAt(workflowRun.getId(), currentNodeCode, workflowRun.getStartedAt(), finishedAt),
                        finishedAt
                );
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
                            recordNodeRunFinish(
                                    workflowRun.getId(),
                                    nextNode.nodeCode(),
                                    nextNode.nodeType(),
                                    nodeProgress.failedCount() == 0,
                                    command.errorCode(),
                                    command.errorMessage(),
                                    finishedAt,
                                    finishedAt
                            );
                        }
                        continue;
                    }
                    workflowNodeDispatchService.dispatchNode(
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
            jobInstanceMapper.updateProgress(
                    command.tenantId(),
                    jobInstance.getId(),
                    instanceStatus,
                    (int) successCount,
                    (int) failedCount,
                    allPartitionsFinished && !dagContinues ? finishedAt : null
                );
            if (workflowRun != null) {
                String workflowEvent = resolveWorkflowEvent(successCount, failedCount, allPartitionsFinished, dagContinues);
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

    /**
     * workflow_run 只允许进入 workflow 语义状态，不复用 job_instance 的 PARTIAL_FAILED 等口径。
     */
    private String resolveWorkflowEvent(long successCount, long failedCount, boolean allPartitionsFinished, boolean dagContinues) {
        if (!allPartitionsFinished) {
            return WorkflowRunStatus.RUNNING.code();
        }
        if (dagContinues) {
            return WorkflowRunStatus.RUNNING.code();
        }
        return failedCount > 0 ? WorkflowRunStatus.FAILED.code() : WorkflowRunStatus.SUCCESS.code();
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

    private Instant finishedAtOrNow() {
        return Instant.now();
    }

    private boolean isWorkerClaimable(String tenantId, String workerCode, JobTaskEntity task) {
        if (workerCode == null || workerCode.isBlank()) {
            return false;
        }
        WorkerRegistryRecord workerRegistry = workerRegistryRepository.findFirstByTenantIdAndWorkerCode(tenantId, workerCode);
        if (workerRegistry == null || !WorkerRegistryStatus.ONLINE.code().equals(workerRegistry.getStatus())) {
            return false;
        }
        if (task == null || task.getJobPartitionId() == null) {
            return true;
        }
        JobPartitionEntity partition = jobPartitionMapper.selectById(tenantId, task.getJobPartitionId());
        if (partition == null || partition.getWorkerGroup() == null || partition.getWorkerGroup().isBlank()) {
            return true;
        }
        return partition.getWorkerGroup().equalsIgnoreCase(workerRegistry.getWorkerGroup());
    }

    private int nextRunSeq(Long workflowRunId, String nodeCode) {
        WorkflowNodeRunEntity current = workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(workflowRunId, nodeCode);
        return current == null || current.getRunSeq() == null ? 1 : current.getRunSeq() + 1;
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

    private record NodePartitionProgress(int partitionCount, long successCount, long failedCount) {

        private boolean allFinished() {
            return partitionCount > 0 && successCount + failedCount == partitionCount;
        }
    }
}
