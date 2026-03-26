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
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * Worker 认领（claim）与租约（lease）治理。
 *
 * <p>该类对外提供的语义是“worker 通过 HTTP 回调 orchestrator 完成 claim/renew”，对应接口为：
 * <ul>
 *   <li>{@code POST /internal/tasks/{taskId}/claim}</li>
 *   <li>{@code POST /internal/tasks/{taskId}/renew}</li>
 * </ul>
 *
 * <p>关键约束：
 * <ul>
 *   <li><strong>同一 task 只能被一个 worker 成功认领</strong>：靠 DB 条件更新（READY → RUNNING）保证并发一致性。</li>
 *   <li><strong>partition 与 task 必须一致</strong>：task 认领成功后同步 claim partition，并写入 lease_expire_at。</li>
 *   <li><strong>step 镜像跟随推进</strong>：若存在 {@code job_step_instance}，一并推进为 RUNNING，避免 UI/审计口径不一致。</li>
 * </ul>
 */
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
    @Override
    @Transactional
    public JobTaskEntity assignWorker(String tenantId, Long taskId, String workerCode) {
        // 入口语义：如果不可认领（worker 不在线/组不匹配/状态不允许），返回 current（由 controller 转换为 409/404）。
        JobTaskEntity current = jobTaskMapper.selectById(tenantId, taskId);
        if (current == null) {
            return null;
        }
        if (!isWorkerClaimable(tenantId, workerCode, current)) {
            return current;
        }
        int updated = jobTaskMapper.assignWorker(tenantId, taskId, workerCode, TaskStatus.RUNNING.code(), TaskStatus.READY.code(), current.getVersion());
        if (updated <= 0) {
            return jobTaskMapper.selectById(tenantId, taskId);
        }
        if (current.getJobPartitionId() != null) {
            // task 与 partition 的 lease 绑定在一起：task 进入 RUNNING 后必须成功 claim partition，否则认为状态不一致。
            JobPartitionEntity partition = jobPartitionMapper.selectById(tenantId, current.getJobPartitionId());
            if (partition == null) {
                // 这里回滚而不是抛异常：语义上属于并发/状态漂移（可重试），不应该把 worker 侧认领请求打成“系统故障”。
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                return jobTaskMapper.selectById(tenantId, taskId);
            }
            int claimed = jobPartitionMapper.claimPartition(
                    tenantId,
                    current.getJobPartitionId(),
                    workerCode,
                    Instant.now().plusSeconds(partitionLeaseProperties.getExpireSeconds()),
                    PartitionStatus.READY.code(),
                    PartitionStatus.RUNNING.code(),
                    partition.getVersion()
            );
            if (claimed <= 0) {
                // 避免出现 “task 已 RUNNING 但 partition 未 RUNNING” 的中间态：回滚本事务，让下一次认领重试来收敛。
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                return jobTaskMapper.selectById(tenantId, taskId);
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
        // 续租语义：只有 RUNNING 且 worker 匹配时允许续租；失败由 controller 统一转成 409。
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
                TaskStatus.SUCCESS.code(), TaskStatus.FAILED.code(), TaskStatus.CANCELLED.code(), TaskStatus.TERMINATED.code(),
                current.getVersion());
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
                TaskStatus.SUCCESS.code(), TaskStatus.FAILED.code(), TaskStatus.CANCELLED.code(), TaskStatus.TERMINATED.code(),
                current.getVersion());
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
