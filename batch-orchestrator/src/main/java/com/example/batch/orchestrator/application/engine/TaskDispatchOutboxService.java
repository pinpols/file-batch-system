package com.example.batch.orchestrator.application.engine;

import com.example.batch.common.enums.OutboxPublishStatus;
import com.example.batch.common.kafka.TaskDispatchMessage;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.entity.OutboxEventEntity;
import com.example.batch.orchestrator.mapper.OutboxEventMapper;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TaskDispatchOutboxService {

    private final OutboxEventMapper outboxEventMapper;

    /**
     * 任务派发统一通过 outbox 落库，避免 launch/retry/reclaim 各自拼装消息协议。
     */
    public void writeDispatchEvent(JobInstanceEntity jobInstance,
                                   JobTaskEntity task,
                                   JobPartitionEntity partition,
                                   String traceId,
                                   String eventKey) {
        TaskDispatchMessage message = new TaskDispatchMessage(
                "v1",
                task.getTenantId(),
                jobInstance.getId(),
                partition.getId(),
                task.getId(),
                jobInstance.getInstanceNo(),
                jobInstance.getJobCode(),
                task.getTaskType(),
                task.getTaskSeq(),
                task.getTaskType(),
                task.getAssignedWorkerCode(),
                resolvePriorityBand(jobInstance.getPriority()),
                partition.getBusinessKey(),
                task.getTaskPayload(),
                traceId,
                partition.getIdempotencyKey(),
                Instant.now()
        );

        OutboxEventEntity event = new OutboxEventEntity();
        event.setTenantId(task.getTenantId());
        event.setAggregateType("JOB_TASK");
        event.setAggregateId(task.getId());
        event.setEventType(task.getTaskType());
        event.setEventKey(eventKey == null || eventKey.isBlank() ? task.getTenantId() + ":" + task.getId() : eventKey);
        event.setPayloadJson(JsonUtils.toJson(message));
        event.setPublishStatus(OutboxPublishStatus.NEW.code());
        event.setPublishAttempt(0);
        event.setTraceId(traceId);
        outboxEventMapper.insert(event);
    }

    private String resolvePriorityBand(Integer priority) {
        if (priority == null || priority <= 3) {
            return com.example.batch.common.enums.SchedulingPriorityBand.HIGH.code();
        }
        if (priority <= 6) {
            return com.example.batch.common.enums.SchedulingPriorityBand.MEDIUM.code();
        }
        return com.example.batch.common.enums.SchedulingPriorityBand.LOW.code();
    }
}
