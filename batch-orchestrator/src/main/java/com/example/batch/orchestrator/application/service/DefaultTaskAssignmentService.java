package com.example.batch.orchestrator.application.service;

import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.StepInstanceStatus;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.common.exception.BizException;
import com.example.batch.orchestrator.config.PartitionLeaseProperties;
import com.example.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobStepInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.entity.WorkerRegistryRecord;
import com.example.batch.orchestrator.domain.query.JobExecutionLogQuery;
import com.example.batch.orchestrator.domain.statemachine.StateMachine;
import com.example.batch.orchestrator.mapper.JobExecutionLogMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.JobStepInstanceMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import com.example.batch.orchestrator.repository.WorkerRegistryRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class DefaultTaskAssignmentService implements TaskAssignmentService {

    private final JobTaskMapper jobTaskMapper;
    private final JobPartitionMapper jobPartitionMapper;
    private final JobStepInstanceMapper jobStepInstanceMapper;
    private final JobExecutionLogMapper jobExecutionLogMapper;
    private final WorkerRegistryRepository workerRegistryRepository;
    private final PartitionLeaseProperties partitionLeaseProperties;
    private final StateMachine<Object> stateMachine;

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
        int updated = jobTaskMapper.assignWorker(tenantId, taskId, workerCode, TaskStatus.RUNNING.code(), TaskStatus.READY.code());
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
        JobStepInstanceEntity stepInstance = jobStepInstanceMapper.selectByJobTaskId(tenantId, taskId);
        if (stepInstance != null
                && jobStepInstanceMapper.markRunning(tenantId, stepInstance.getId(), Instant.now(), stepInstance.getVersion(), StepInstanceStatus.RUNNING.code(), StepInstanceStatus.CREATED.code(), StepInstanceStatus.WAITING.code(), StepInstanceStatus.READY.code(), StepInstanceStatus.RETRYING.code()) <= 0) {
            throw new BizException(ResultCode.STATE_CONFLICT, "job step instance claim conflict");
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
        jobTaskMapper.updateStatus(tenantId, taskId, taskStatus, null, errorCode, errorMessage,
                TaskStatus.SUCCESS.code(), TaskStatus.FAILED.code(), TaskStatus.CANCELLED.code(), TaskStatus.TERMINATED.code());
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
        jobTaskMapper.updateStatus(tenantId, taskId, TaskStatus.RUNNING.code(), null, null, null,
                TaskStatus.SUCCESS.code(), TaskStatus.FAILED.code(), TaskStatus.CANCELLED.code(), TaskStatus.TERMINATED.code());
        JobStepInstanceEntity stepInstance = jobStepInstanceMapper.selectByJobTaskId(tenantId, taskId);
        if (stepInstance != null
                && jobStepInstanceMapper.markRunning(tenantId, stepInstance.getId(), startedAt, stepInstance.getVersion(), StepInstanceStatus.RUNNING.code(), StepInstanceStatus.CREATED.code(), StepInstanceStatus.WAITING.code(), StepInstanceStatus.READY.code(), StepInstanceStatus.RETRYING.code()) <= 0) {
            throw new BizException(ResultCode.STATE_CONFLICT, "job step instance running conflict");
        }
        return jobTaskMapper.selectById(tenantId, taskId);
    }

    private boolean isWorkerClaimable(String tenantId, String workerCode, JobTaskEntity task) {
        if (workerCode == null || workerCode.isBlank()) {
            return false;
        }
        WorkerRegistryRecord workerRegistry = workerRegistryRepository.findFirstByTenantIdAndWorkerCode(tenantId, workerCode);
        if (workerRegistry == null || !WorkerRegistryStatus.ONLINE.code().equals(workerRegistry.status())) {
            return false;
        }
        if (task == null || task.getJobPartitionId() == null) {
            return true;
        }
        JobPartitionEntity partition = jobPartitionMapper.selectById(tenantId, task.getJobPartitionId());
        if (partition == null || partition.getWorkerGroup() == null || partition.getWorkerGroup().isBlank()) {
            return true;
        }
        return partition.getWorkerGroup().equalsIgnoreCase(workerRegistry.workerGroup());
    }
}
