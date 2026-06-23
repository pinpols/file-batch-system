package io.github.pinpols.batch.trigger.event;

import io.github.pinpols.batch.common.enums.OutboxPublishStatus;
import io.github.pinpols.batch.common.event.DomainEvent;
import io.github.pinpols.batch.common.event.DomainEventPublisher;
import io.github.pinpols.batch.common.persistence.entity.TriggerOutboxEventEntity;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.trigger.mapper.TriggerOutboxEventMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * trigger 模块的 {@link DomainEventPublisher} 实现 — 把 {@link DomainEvent} 写入 {@code
 * trigger_outbox_event} 表(ADR-010 trigger 异步解耦事件),与 orchestrator 的 {@code
 * OutboxDomainEventPublisher} 走 {@code outbox_event} 表互不重叠,守住三表分工:
 *
 * <ul>
 *   <li>{@code outbox_event} — 业务通用事件,orchestrator 写
 *   <li>{@code trigger_outbox_event} — trigger fire → orchestrator launch 调度事件,trigger 写
 *   <li>{@code event_outbox_retry} — 投递失败退避重试,relay 写
 * </ul>
 *
 * 适配关系:
 *
 * <ul>
 *   <li>{@link DomainEvent#tenantId()} → 表 tenant_id
 *   <li>{@link DomainEvent#eventKey()} → 表 request_id(dedup key,UNIQUE (tenant_id, request_id))
 *   <li>{@link DomainEvent#payload()} → JSON 序列化后落 payload(JSONB)
 *   <li>{@link DomainEvent#traceId()} → 表 trace_id
 *   <li>topic: pilot 阶段用 V80 默认 {@code batch.trigger.launch.v1};未来需多 topic 时, 由 trigger 模块在
 *       DomainEvent.payload 加约定 key 或重载本类提供 publish(event, topic)
 * </ul>
 *
 * <p>{@code @Transactional(propagation = MANDATORY)} 强制必须在 trigger_request 同事务内调用,
 * 防止"trigger_request 写入数据库但 outbox 事件丢失" — 见 V80 不变量。
 */
@Service
@RequiredArgsConstructor
public class TriggerOutboxDomainEventPublisher implements DomainEventPublisher {

  /** V80 默认 topic;trigger 当前唯一事件类型 = trigger.launch.v1。 */
  private static final String DEFAULT_TOPIC = "batch.trigger.launch.v1";

  private final TriggerOutboxEventMapper triggerOutboxEventMapper;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Long publish(DomainEvent event) {
    return publishRaw(
        event.tenantId(), event.eventKey(), event.traceId(), JsonUtils.toJson(event.payload()));
  }

  /**
   * 性能优化入口:调用方已有 JSON 串(如 {@link io.github.pinpols.batch.common.dto.LaunchEnvelope} 序列化结果) 时直接传入,跳过
   * DomainEvent.payload Map ↔ record 来回两次序列化。
   *
   * <p>语义与 {@link #publish(DomainEvent)} 等价 — 都是同事务写入 trigger_outbox_event 一行。
   *
   * <p>同样要求 {@code @Transactional(propagation = MANDATORY)} 守护 — 必须在 trigger_request 同事务内调用,防止
   * outbox 事件与父记录不一致。
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Long publishRaw(String tenantId, String requestId, String traceId, String payloadJson) {
    TriggerOutboxEventEntity entity = new TriggerOutboxEventEntity();
    entity.setTenantId(tenantId);
    entity.setRequestId(requestId);
    entity.setTopic(DEFAULT_TOPIC);
    entity.setPayload(payloadJson);
    entity.setPublishStatus(OutboxPublishStatus.NEW.code());
    entity.setPublishAttempt(0);
    entity.setTraceId(traceId);
    entity.setNextPublishAt(BatchDateTimeSupport.utcNow());
    triggerOutboxEventMapper.insert(entity);
    return entity.getId();
  }
}
