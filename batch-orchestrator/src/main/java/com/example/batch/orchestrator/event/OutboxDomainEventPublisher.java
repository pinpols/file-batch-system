package com.example.batch.orchestrator.event;

import com.example.batch.common.enums.OutboxPublishStatus;
import com.example.batch.common.event.DomainEvent;
import com.example.batch.common.event.DomainEventPublisher;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.domain.entity.OutboxEventEntity;
import com.example.batch.orchestrator.mapper.OutboxEventMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link DomainEventPublisher} 在 orchestrator 的实现:把 {@link DomainEvent} 转换为 {@link
 * OutboxEventEntity} 并 insert {@code outbox_event} 表。
 *
 * <p>{@code @Transactional(propagation = MANDATORY)} 强制必须在调用方事务内,违反 outbox 模式(同事务原子)直接抛
 * IllegalTransactionStateException。
 *
 * <p>不处理 {@code trigger_outbox_event}(trigger 模块自己有 publisher)。
 */
@Service
@RequiredArgsConstructor
public class OutboxDomainEventPublisher implements DomainEventPublisher {

  private final OutboxEventMapper outboxEventMapper;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Long publish(DomainEvent event) {
    OutboxEventEntity entity = new OutboxEventEntity();
    entity.setTenantId(event.tenantId());
    entity.setAggregateType(event.aggregateType());
    entity.setAggregateId(event.aggregateId());
    entity.setEventType(event.eventType());
    entity.setEventKey(event.eventKey());
    entity.setPayloadJson(JsonUtils.toJson(event.payload()));
    entity.setPublishStatus(OutboxPublishStatus.NEW.code());
    entity.setPublishAttempt(0);
    entity.setTraceId(event.traceId());
    if (event.priority() != null) {
      entity.setPriority(event.priority());
    }
    outboxEventMapper.insert(entity);
    return entity.getId();
  }
}
