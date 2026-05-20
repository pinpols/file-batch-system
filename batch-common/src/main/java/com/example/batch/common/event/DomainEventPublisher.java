package com.example.batch.common.event;

/**
 * 域事件发布器。各模块按自己的 outbox 表实现:
 *
 * <ul>
 *   <li>batch-orchestrator → {@code OutboxDomainEventPublisher} 写 {@code outbox_event}
 *   <li>batch-trigger → 单独的 publisher 写 {@code trigger_outbox_event}(trigger fire 调度事件)
 * </ul>
 *
 * <p>调用约束(同 outbox 三表分工政策):必须在业务事务内调用,否则违反 outbox 模式不变量(同事务原子)。各实现应加
 * {@code @Transactional(propagation = MANDATORY)} 强制。
 */
public interface DomainEventPublisher {

  /** 把 {@link DomainEvent} 落到对应 outbox 表,返回插入的 outbox 行 id(可为 null,若实现不需要)。 */
  Long publish(DomainEvent event);
}
