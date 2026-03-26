package com.example.batch.orchestrator.application.service;

import com.example.batch.common.enums.DeadLetterReplayStatus;
import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.common.enums.RetryPolicyType;
import com.example.batch.common.enums.RetryScheduleStatus;
import com.example.batch.common.enums.StepInstanceStatus;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import com.example.batch.orchestrator.config.RetryGovernanceProperties;
import com.example.batch.orchestrator.domain.entity.DeadLetterTaskEntity;
import com.example.batch.orchestrator.domain.entity.JobDefinitionRecord;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobStepInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.entity.RetryScheduleEntity;
import com.example.batch.orchestrator.domain.query.JobTaskQuery;
import com.example.batch.orchestrator.domain.query.RetryScheduleQuery;
import com.example.batch.orchestrator.mapper.DeadLetterTaskMapper;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.JobStepInstanceMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import com.example.batch.orchestrator.mapper.RetryScheduleMapper;
import com.example.batch.orchestrator.repository.JobDefinitionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 失败重试与死信治理（Orchestrator 侧）。
 *
 * <p>总体策略：
 * <ul>
 *   <li>task 执行失败后（worker report）由治理层决定是否进入重试：写 {@code retry_schedule} 或写 {@code dead_letter_task}。</li>
 *   <li>重试不是“原地把 task 置回 READY”这么简单，而是通过重排队（requeue）统一回到 outbox → Kafka 的派发链路。</li>
 * </ul>
 *
 * <p>为什么要通过 outbox 回流：
 * <ul>
 *   <li>避免多处（launch / retry / reclaim）各自拼 Kafka 消息协议，减少漂移风险。</li>
 *   <li>事务边界清晰：DB 状态更新与 outbox 落库同事务，Kafka 投递由 forwarder 异步完成。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultRetryGovernanceService implements RetryGovernanceService {

    private final RetryScheduleMapper retryScheduleMapper;
    private final DeadLetterTaskMapper deadLetterTaskMapper;
    private final JobDefinitionRepository jobDefinitionRepository;
    private final JobTaskMapper jobTaskMapper;
    private final JobPartitionMapper jobPartitionMapper;
    private final JobInstanceMapper jobInstanceMapper;
    private final JobStepInstanceMapper jobStepInstanceMapper;
    private final TaskDispatchOutboxService taskDispatchOutboxService;
    private final RetryGovernanceProperties retryGovernanceProperties;

    @Override
    @Transactional
    public boolean scheduleRetryIfNecessary(JobTaskEntity task,
                                            JobPartitionEntity partition,
                                            JobInstanceEntity jobInstance,
                                            String errorCode,
                                            String errorMessage) {
        // 入口语义：返回 true 表示“已进入 RETRYING（有后续重排队）”，返回 false 表示“本次失败将进入终态（dead-letter 或直接失败）”。
        if (task == null || partition == null || jobInstance == null) {
            return false;
        }
        RetryPolicyPlan retryPolicyPlan = resolveRetryPolicy(jobInstance.getJobDefinitionId());
        if (RetryPolicyType.NONE.code().equals(retryPolicyPlan.retryPolicy())
                || retryPolicyPlan.maxRetryCount() <= 0) {
            // 无重试预算：直接进入死信，交由人工/审批/补偿处理。
            createDeadLetter(task, partition, jobInstance, errorCode, errorMessage);
            return false;
        }

        int nextRetryCount = Optional.ofNullable(partition.getRetryCount()).orElse(0) + 1;
        if (nextRetryCount > retryPolicyPlan.maxRetryCount()) {
            // 重试耗尽：落死信（不会回到正常 dispatch）。
            createDeadLetter(task, partition, jobInstance, errorCode, errorMessage);
            return false;
        }

        RetryScheduleEntity retrySchedule = new RetryScheduleEntity();
        retrySchedule.setTenantId(task.getTenantId());
        retrySchedule.setRelatedType("JOB_PARTITION");
        retrySchedule.setRelatedId(partition.getId());
        retrySchedule.setRetryPolicy(retryPolicyPlan.retryPolicy());
        retrySchedule.setRetryCount(nextRetryCount);
        retrySchedule.setMaxRetryCount(retryPolicyPlan.maxRetryCount());
        retrySchedule.setNextRetryAt(calculateNextRetryAt(retryPolicyPlan.retryPolicy(), nextRetryCount));
        retrySchedule.setRetryStatus(RetryScheduleStatus.WAITING.code());
        retrySchedule.setDedupKey(task.getTenantId() + ":" + partition.getId() + ":" + nextRetryCount);
        retrySchedule.setLastErrorCode(errorCode);
        retrySchedule.setLastErrorMessage(errorMessage);
        retryScheduleMapper.insert(retrySchedule);
        log.info("retry scheduled: tenantId={}, partitionId={}, retryCount={}",
                task.getTenantId(), partition.getId(), nextRetryCount);
        return true;
    }

    @Override
    @Transactional
    public void dispatchDueRetries() {
        // 定时扫描：把到期的 retry_schedule 记录重排队（requeue）回 outbox。
        List<RetryScheduleEntity> dueRetries = retryScheduleMapper.selectByQuery(new RetryScheduleQuery(
                null,
                RetryScheduleStatus.WAITING.code(),
                Instant.now(),
                retryGovernanceProperties.getBatchSize()
        ));
        for (RetryScheduleEntity retrySchedule : dueRetries) {
            if (retryScheduleMapper.markRunning(retrySchedule.getId(), RetryScheduleStatus.WAITING.code(), RetryScheduleStatus.RUNNING.code()) <= 0) {
                continue;
            }
            try {
                requeuePartition(retrySchedule);
                retryScheduleMapper.markSuccess(retrySchedule.getId(), RetryScheduleStatus.SUCCESS.code());
            } catch (Exception exception) {
                log.warn("retry dispatch failed: retryId={}, error={}", retrySchedule.getId(), exception.getMessage(), exception);
                retryScheduleMapper.markFailed(
                        retrySchedule.getId(),
                        RetryScheduleStatus.FAILED.code(),
                        "RETRY_DISPATCH_FAILED",
                        exception.getMessage(),
                        null
                );
            }
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void retryPartition(String tenantId, Long partitionId, String eventKey) {
        requeuePartition(tenantId, partitionId, eventKey);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void retryTask(String tenantId, Long taskId, String eventKey) {
        JobTaskEntity task = jobTaskMapper.selectById(tenantId, taskId);
        if (task == null) {
            throw new IllegalStateException("retry task not found");
        }
        if (task.getJobPartitionId() != null) {
            requeuePartition(tenantId, task.getJobPartitionId(), eventKey);
            return;
        }
        requeueTaskWithoutPartition(tenantId, task, eventKey);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void replayDeadLetter(String tenantId, Long deadLetterTaskId) {
        DeadLetterTaskEntity deadLetterTask = deadLetterTaskMapper.selectById(tenantId, deadLetterTaskId);
        if (deadLetterTask == null) {
            throw new IllegalStateException("dead letter task not found");
        }
        if (!DeadLetterReplayStatus.NEW.code().equals(deadLetterTask.getReplayStatus())
                && !DeadLetterReplayStatus.FAILED.code().equals(deadLetterTask.getReplayStatus())) {
            throw new IllegalStateException("dead letter task is not replayable");
        }
        if (deadLetterTaskMapper.markReplaying(
                tenantId,
                deadLetterTaskId,
                deadLetterTask.getReplayStatus(),
                DeadLetterReplayStatus.REPLAYING.code()) <= 0) {
            throw new IllegalStateException("dead letter task replay conflict");
        }
        Instant replayAt = Instant.now();
        int replayCount = Optional.ofNullable(deadLetterTask.getReplayCount()).orElse(0) + 1;
        try {
            if (!"JOB_PARTITION".equals(deadLetterTask.getSourceType())) {
                throw new IllegalStateException("unsupported dead letter source type: " + deadLetterTask.getSourceType());
            }
            requeuePartition(tenantId, deadLetterTask.getSourceId(), tenantId + ":dead-letter:" + deadLetterTaskId);
            deadLetterTaskMapper.markReplaySuccess(
                    tenantId,
                    deadLetterTaskId,
                    DeadLetterReplayStatus.SUCCESS.code(),
                    replayCount,
                    replayAt,
                    "REPLAY_ACCEPTED"
            );
        } catch (Exception exception) {
            log.warn("dead letter replay failed: deadLetterId={}, error={}", deadLetterTaskId, exception.getMessage(), exception);
            deadLetterTaskMapper.markReplayFailure(
                    tenantId,
                    deadLetterTaskId,
                    DeadLetterReplayStatus.FAILED.code(),
                    replayCount,
                    replayAt,
                    exception.getMessage()
            );
            throw exception;
        }
    }

    private void requeuePartition(RetryScheduleEntity retrySchedule) {
        requeuePartition(
                retrySchedule.getTenantId(),
                retrySchedule.getRelatedId(),
                retrySchedule.getTenantId() + ":retry:" + retrySchedule.getId()
        );
    }

    private void requeuePartition(String tenantId, Long partitionId, String eventKey) {
        JobPartitionEntity partition = jobPartitionMapper.selectById(tenantId, partitionId);
        if (partition == null) {
            throw new IllegalStateException("retry partition not found");
        }
        JobInstanceEntity jobInstance = jobInstanceMapper.selectById(tenantId, partition.getJobInstanceId());
        if (jobInstance == null) {
            throw new IllegalStateException("retry job instance not found");
        }
        List<JobTaskEntity> tasks = jobTaskMapper.selectByQuery(new JobTaskQuery(
                tenantId,
                jobInstance.getId(),
                partition.getId(),
                null,
                null
        ));
        JobTaskEntity task = tasks.stream()
                .sorted((left, right) -> Integer.compare(
                        left.getTaskSeq() == null ? 0 : left.getTaskSeq(),
                        right.getTaskSeq() == null ? 0 : right.getTaskSeq()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("retry task not found"));

        JobStepInstanceEntity stepInstance = jobStepInstanceMapper.selectByJobTaskId(tenantId, task.getId());
        if (stepInstance != null) {
            int nextRetryCount = Optional.ofNullable(stepInstance.getRetryCount()).orElse(0) + 1;
            jobStepInstanceMapper.resetForRetryByJobTaskId(tenantId, task.getId(), nextRetryCount, StepInstanceStatus.READY.code());
        }
        jobPartitionMapper.resetForDispatch(tenantId, partition.getId(), PartitionStatus.READY.code(), partition.getVersion());
        jobTaskMapper.resetForRetry(tenantId, task.getId(), TaskStatus.READY.code(), task.getVersion());
        taskDispatchOutboxService.writeDispatchEvent(
                jobInstance,
                task,
                partition,
                jobInstance.getTraceId(),
                eventKey
        );
    }

    private void requeueTaskWithoutPartition(String tenantId, JobTaskEntity task, String eventKey) {
        JobInstanceEntity jobInstance = jobInstanceMapper.selectById(tenantId, task.getJobInstanceId());
        if (jobInstance == null) {
            throw new IllegalStateException("retry job instance not found");
        }
        JobStepInstanceEntity stepInstance = jobStepInstanceMapper.selectByJobTaskId(tenantId, task.getId());
        if (stepInstance != null) {
            int nextRetryCount = Optional.ofNullable(stepInstance.getRetryCount()).orElse(0) + 1;
            jobStepInstanceMapper.resetForRetryByJobTaskId(tenantId, task.getId(), nextRetryCount, StepInstanceStatus.READY.code());
        }
        jobTaskMapper.resetForRetry(tenantId, task.getId(), TaskStatus.READY.code(), task.getVersion());
        taskDispatchOutboxService.writeDispatchEvent(
                jobInstance,
                task,
                null,
                jobInstance.getTraceId(),
                eventKey
        );
    }

    private RetryPolicyPlan resolveRetryPolicy(Long jobDefinitionId) {
        if (jobDefinitionId == null) {
            return new RetryPolicyPlan(RetryPolicyType.FIXED.code(), retryGovernanceProperties.getDefaultMaxRetryCount());
        }
        JobDefinitionRecord jobDefinitionRecord = jobDefinitionRepository.findById(jobDefinitionId).orElse(null);
        if (jobDefinitionRecord == null) {
            return new RetryPolicyPlan(RetryPolicyType.FIXED.code(), retryGovernanceProperties.getDefaultMaxRetryCount());
        }
        String retryPolicy = jobDefinitionRecord.retryPolicy();
        Integer retryMaxCount = jobDefinitionRecord.retryMaxCount();
        if (retryPolicy == null || retryPolicy.isBlank()) {
            retryPolicy = RetryPolicyType.FIXED.code();
        }
        return new RetryPolicyPlan(retryPolicy, retryMaxCount == null ? retryGovernanceProperties.getDefaultMaxRetryCount() : retryMaxCount);
    }

    /**
     * 重试时间由治理层统一计算，避免调度器和业务处理各自散落一套 backoff 规则。
     */
    private Instant calculateNextRetryAt(String retryPolicy, int retryCount) {
        long delaySeconds = retryGovernanceProperties.getFixedDelaySeconds();
        if (RetryPolicyType.EXPONENTIAL.code().equalsIgnoreCase(retryPolicy)) {
            long multiplier = Math.max(1L, retryGovernanceProperties.getExponentialMultiplier());
            long candidate = delaySeconds;
            for (int i = 1; i < retryCount; i++) {
                if (candidate >= retryGovernanceProperties.getMaxDelaySeconds()) {
                    candidate = retryGovernanceProperties.getMaxDelaySeconds();
                    break;
                }
                candidate = Math.min(
                        retryGovernanceProperties.getMaxDelaySeconds(),
                        candidate * multiplier
                );
            }
            delaySeconds = candidate;
        }
        return Instant.now().plusSeconds(delaySeconds);
    }

    private void createDeadLetter(JobTaskEntity task,
                                  JobPartitionEntity partition,
                                  JobInstanceEntity jobInstance,
                                  String errorCode,
                                  String errorMessage) {
        DeadLetterTaskEntity deadLetterTask = new DeadLetterTaskEntity();
        deadLetterTask.setTenantId(task.getTenantId());
        deadLetterTask.setSourceType("JOB_PARTITION");
        deadLetterTask.setSourceId(partition.getId());
        deadLetterTask.setDeadLetterReason(buildDeadLetterReason(errorCode, errorMessage));
        deadLetterTask.setPayloadRef(jobInstance.getInstanceNo() + ":" + task.getId());
        deadLetterTask.setReplayStatus(DeadLetterReplayStatus.NEW.code());
        deadLetterTask.setReplayCount(0);
        deadLetterTask.setTraceId(jobInstance.getTraceId());
        deadLetterTaskMapper.insert(deadLetterTask);
        log.warn("dead letter created: tenantId={}, partitionId={}, instanceNo={}",
                task.getTenantId(), partition.getId(), jobInstance.getInstanceNo());
    }

    private String buildDeadLetterReason(String errorCode, String errorMessage) {
        String code = errorCode == null ? "UNKNOWN" : errorCode;
        String message = errorMessage == null ? "retry exhausted" : errorMessage;
        return code + ": " + message;
    }

    private record RetryPolicyPlan(String retryPolicy, int maxRetryCount) {
    }
}
