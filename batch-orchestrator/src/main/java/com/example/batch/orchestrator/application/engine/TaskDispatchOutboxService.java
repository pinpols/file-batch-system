package com.example.batch.orchestrator.application.engine;

import com.example.batch.common.context.RunModeSupport;
import com.example.batch.common.enums.RunMode;
import com.example.batch.common.enums.SchedulingPriorityBand;
import com.example.batch.common.event.DomainEvent;
import com.example.batch.common.event.DomainEventPublisher;
import com.example.batch.common.kafka.SchedulingContext;
import com.example.batch.common.kafka.TaskDispatchMessage;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.application.service.workflow.BizDateArithmetic;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
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

  private final DomainEventPublisher domainEventPublisher;
  private final JobTaskMapper jobTaskMapper;
  private final BizDateArithmetic bizDateArithmetic;

  @Lazy @Autowired private TaskDispatchOutboxService self;

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
    self.writeDispatchEvent(jobInstance, task, partition, traceId, eventKey, null);
  }

  /**
   * 带 {@code runModeOverride} 的写入重载：retry / reclaim 等再派发场景可把 run_mode 注入到任务 payload 中， worker
   * 据此区分"首次执行"与"补偿/重放"行为；传 null 保留 payload 原状。
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void writeDispatchEvent(
      JobInstanceEntity jobInstance,
      JobTaskEntity task,
      JobPartitionEntity partition,
      String traceId,
      String eventKey,
      RunMode runModeOverride) {
    Long jobPartitionId = partition != null ? partition.getId() : null;
    String idempotencyKey =
        partition != null
            ? partition.getIdempotencyKey()
            : resolveIdempotencyKeyWithoutPartition(task, eventKey);
    // P1-2.2:v2 消息瘦身,业务字段(payload/businessKey/taskSeq/highWaterMarkIn)走 worker CLAIM
    // 时 EffectiveTaskConfig 实时读 DB;此处只保留 task key + 路由元数据。
    // runModeOverride 在 v1 时通过 payload 注入,v2 后改由 worker CLAIM 读 job_task.task_payload —
    // retry/reclaim 路径需要把 run_mode 持久化到 task_payload 而非塞进 message 临时透传。
    persistRunModeOverride(task, runModeOverride);
    TaskDispatchMessage message =
        new TaskDispatchMessage(
            "v2",
            task.getTenantId(),
            jobInstance.getId(),
            jobPartitionId,
            task.getId(),
            jobInstance.getInstanceNo(),
            jobInstance.getJobCode(),
            task.getTaskType(),
            task.getAssignedWorkerCode(),
            resolvePriorityBand(jobInstance.getPriority()),
            traceId,
            idempotencyKey,
            BatchDateTimeSupport.utcNow(),
            buildSchedulingContext(jobInstance));

    // V88: priority 拷到 outbox_event,OutboxPollScheduler 按 priority desc 排序优先派发。
    // 优先级源:task.priority (V88 加列,DefaultPartitionDispatchService.buildTask 设置);
    // 兜底:jobInstance.priority(老逻辑);都缺走 DomainEvent 不传 → DB DEFAULT 5。
    Integer priority = task.getPriority() != null ? task.getPriority() : jobInstance.getPriority();
    String resolvedKey =
        eventKey == null || eventKey.isBlank() ? task.getTenantId() + ":" + task.getId() : eventKey;
    domainEventPublisher.publish(
        DomainEvent.builder(task.getTenantId())
            .aggregate("JOB_TASK", task.getId())
            .type(task.getTaskType())
            .key(resolvedKey)
            .payload(JsonUtils.toMap(message))
            .traceId(traceId)
            .priority(priority)
            .build());
  }

  /**
   * SDK Phase 2 §2.1:构造随消息下沉的调度上下文。
   *
   * <p>仅填派发时刻已确定的不可变事实:
   *
   * <ul>
   *   <li>bizDate / prevBizDate / nextBizDate:业务日及前后工作日(BizDateArithmetic 周末近似,暂不感知节假日);
   *   <li>isHoliday:当前 = bizDate 是否周末(business_calendar 接入前的近似语义);
   *   <li>attemptNo / triggerType:取自 job_instance;
   *   <li>triggerCode / workflowRunId:平台暂无来源列,置 null,待后续补列。
   * </ul>
   */
  private SchedulingContext buildSchedulingContext(JobInstanceEntity jobInstance) {
    LocalDate bizDate = jobInstance.getBizDate();
    LocalDate prevBizDate = bizDateArithmetic.previousBusinessDay(bizDate);
    LocalDate nextBizDate = bizDateArithmetic.nextBusinessDay(bizDate);
    Boolean isHoliday = bizDate != null ? bizDateArithmetic.isWeekend(bizDate) : null;
    return new SchedulingContext(
        bizDate,
        prevBizDate,
        nextBizDate,
        isHoliday,
        jobInstance.getRunAttempt(),
        jobInstance.getTriggerType(),
        null,
        null);
  }

  /**
   * P1-2.2:把 RunMode 持久化到 job_task.task_payload。worker CLAIM 时 EffectiveTaskConfig 实时读
   * task_payload, 看到 run_mode 即可区分"首次执行"与"补偿/重放"。仅 retry/reclaim 等再派发场景调用(runModeOverride 非 null)。
   */
  private void persistRunModeOverride(JobTaskEntity task, RunMode runModeOverride) {
    if (runModeOverride == null || task == null || task.getId() == null) {
      return;
    }
    Map<String, Object> payload = parsePayloadMap(task.getTaskPayload());
    RunModeSupport.putCanonical(payload, runModeOverride);
    String merged = JsonUtils.toJson(payload);
    jobTaskMapper.updatePayload(task.getTenantId(), task.getId(), merged);
    // 同步内存对象,避免后续逻辑读到陈旧 payload
    task.setTaskPayload(merged);
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
      SwallowedExceptionLogger.warn(
          TaskDispatchOutboxService.class, "catch:RuntimeException", ignored);

      // 载荷 JSON 异常时忽略并退回空 Map，仍可为消息打上 run_mode。
    }
    return new LinkedHashMap<>();
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
