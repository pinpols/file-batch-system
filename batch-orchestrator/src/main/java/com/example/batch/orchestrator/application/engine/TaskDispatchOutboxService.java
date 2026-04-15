package com.example.batch.orchestrator.application.engine;

import com.example.batch.common.context.RunModeSupport;
import com.example.batch.common.enums.OutboxPublishStatus;
import com.example.batch.common.enums.RunMode;
import com.example.batch.common.enums.SchedulingPriorityBand;
import com.example.batch.common.kafka.TaskDispatchMessage;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.entity.OutboxEventEntity;
import com.example.batch.orchestrator.mapper.OutboxEventMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox 写入：把“要派发的任务消息”以统一协议落库到 {@code batch.outbox_event}。
 *
 * <p>为什么要统一走 outbox：
 *
 * <ul>
 *   <li><strong>协议收口</strong>：launch / retry / reclaim 等入口都复用同一套消息结构（{@link TaskDispatchMessage}）。
 *   <li><strong>事务一致性</strong>：DB 状态更新与 outbox 落库在同一事务；Kafka 投递由 forwarder 异步完成。
 *   <li><strong>可审计可补偿</strong>：消息投递记录（delivery log）与失败重试均可追溯。
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class TaskDispatchOutboxService {

  private final OutboxEventMapper outboxEventMapper;

  /**
   * 写入一条“任务派发事件”到 outbox。
   *
   * <p>约束/语义：
   *
   * <ul>
   *   <li>{@code eventKey} 是 outbox 的幂等键（同一个 eventKey 重复写入会被上层避免；DB 层也有唯一约束兜底）。
   *   <li>{@code idempotencyKey} 优先使用 partition 的幂等键（保证同分片重复派发不会导致多次执行）。
   *   <li>当无 partition（极少数场景）时，退化使用 {@code tenantId:taskId} 作为幂等键。
   * </ul>
   */
  // D-2: MANDATORY 传播级别强制 outbox 写入必须在调用方事务内执行，无事务时直接抛异常
  @Transactional(propagation = Propagation.MANDATORY)
  public void writeDispatchEvent(
      JobInstanceEntity jobInstance,
      JobTaskEntity task,
      JobPartitionEntity partition,
      String traceId,
      String eventKey) {
    writeDispatchEvent(jobInstance, task, partition, traceId, eventKey, null);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void writeDispatchEvent(
      JobInstanceEntity jobInstance,
      JobTaskEntity task,
      JobPartitionEntity partition,
      String traceId,
      String eventKey,
      RunMode runModeOverride) {
    Long jobPartitionId = partition != null ? partition.getId() : null;
    String businessKey =
        partition != null
            ? partition.getBusinessKey()
            : buildFallbackBusinessKey(jobInstance, task);
    String idempotencyKey =
        partition != null
            ? partition.getIdempotencyKey()
            : resolveIdempotencyKeyWithoutPartition(task, eventKey);
    String taskPayload =
        resolveDispatchPayload(task == null ? null : task.getTaskPayload(), runModeOverride);
    TaskDispatchMessage message =
        new TaskDispatchMessage(
            "v1",
            task.getTenantId(),
            jobInstance.getId(),
            jobPartitionId,
            task.getId(),
            jobInstance.getInstanceNo(),
            jobInstance.getJobCode(),
            task.getTaskType(),
            task.getTaskSeq(),
            task.getTaskType(),
            task.getAssignedWorkerCode(),
            resolvePriorityBand(jobInstance.getPriority()),
            businessKey,
            taskPayload,
            traceId,
            idempotencyKey,
            Instant.now());

    OutboxEventEntity event = new OutboxEventEntity();
    event.setTenantId(task.getTenantId());
    event.setAggregateType("JOB_TASK");
    event.setAggregateId(task.getId());
    event.setEventType(task.getTaskType());
    event.setEventKey(
        eventKey == null || eventKey.isBlank()
            ? task.getTenantId() + ":" + task.getId()
            : eventKey);
    event.setPayloadJson(JsonUtils.toJson(message));
    event.setPublishStatus(OutboxPublishStatus.NEW.code());
    event.setPublishAttempt(0);
    event.setTraceId(traceId);
    outboxEventMapper.insert(event);
  }

  private String resolveDispatchPayload(String payloadJson, RunMode runModeOverride) {
    if (runModeOverride == null) {
      return payloadJson == null ? "{}" : payloadJson;
    }
    Map<String, Object> payload = parsePayloadMap(payloadJson);
    RunModeSupport.putCanonical(payload, runModeOverride);
    return JsonUtils.toJson(payload);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parsePayloadMap(String payloadJson) {
    if (payloadJson == null || payloadJson.isBlank()) {
      return new LinkedHashMap<>();
    }
    try {
      Object payloadObject = JsonUtils.fromJson(payloadJson, Object.class);
      if (payloadObject instanceof Map<?, ?> payloadMap) {
        return new LinkedHashMap<>((Map<String, Object>) payloadMap);
      }
    } catch (RuntimeException ignored) {
      // 载荷 JSON 异常时忽略并退回空 Map，仍可为消息打上 run_mode。
    }
    return new LinkedHashMap<>();
  }

  private static String buildFallbackBusinessKey(
      JobInstanceEntity jobInstance, JobTaskEntity task) {
    String instanceNo =
        jobInstance != null && jobInstance.getInstanceNo() != null
            ? jobInstance.getInstanceNo()
            : "unknown";
    return instanceNo + ":task:" + task.getId();
  }

  // C-9.1: idempotencyKey 不含 version，避免 Kafka 投递成功但 DB 回滚时因 version 变化生成新幂等键导致重复执行
  private static String resolveIdempotencyKeyWithoutPartition(JobTaskEntity task, String eventKey) {
    if (eventKey != null && !eventKey.isBlank()) {
      return eventKey;
    }
    return task.getTenantId() + ":task:" + task.getId() + ":instance:" + task.getJobInstanceId();
  }

  private String resolvePriorityBand(Integer priority) {
    if (priority == null || priority <= 3) {
      return SchedulingPriorityBand.HIGH.code();
    }
    if (priority <= 6) {
      return SchedulingPriorityBand.MEDIUM.code();
    }
    return SchedulingPriorityBand.LOW.code();
  }
}
