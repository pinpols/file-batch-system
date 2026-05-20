package com.example.batch.trigger.event;

import com.example.batch.common.enums.OutboxPublishStatus;
import com.example.batch.common.event.DomainEvent;
import com.example.batch.common.event.DomainEventPublisher;
import com.example.batch.common.persistence.entity.TriggerOutboxEventEntity;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.trigger.mapper.TriggerOutboxEventMapper;
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
 * 防止"trigger_request 落库但 outbox 事件丢失" — 见 V80 不变量。
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
    TriggerOutboxEventEntity entity = new TriggerOutboxEventEntity();
    entity.setTenantId(event.tenantId());
    entity.setRequestId(event.eventKey());
    entity.setTopic(DEFAULT_TOPIC);
    entity.setPayload(JsonUtils.toJson(event.payload()));
    entity.setPublishStatus(OutboxPublishStatus.NEW.code());
    entity.setPublishAttempt(0);
    entity.setTraceId(event.traceId());
    entity.setNextPublishAt(BatchDateTimeSupport.utcNow());
    triggerOutboxEventMapper.insert(entity);
    return entity.getId();
  }
}
