package io.github.pinpols.batch.trigger.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.OutboxPublishStatus;
import io.github.pinpols.batch.common.event.DomainEvent;
import io.github.pinpols.batch.common.persistence.entity.TriggerOutboxEventEntity;
import io.github.pinpols.batch.trigger.mapper.TriggerOutboxEventMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * 守护 TriggerOutboxDomainEventPublisher 把 DomainEvent 准确投影到 trigger_outbox_event 行的适配语义。
 *
 * <p>Propagation.MANDATORY 守护(无事务时抛 IllegalTransactionStateException)由 Spring 容器在 集成测中验证 — 单测直接调
 * publisher 不走 AOP,断言意义不大,放 IT 层覆盖。
 */
class TriggerOutboxDomainEventPublisherTest {

  @Mock private TriggerOutboxEventMapper triggerOutboxEventMapper;

  private TriggerOutboxDomainEventPublisher publisher;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    publisher = new TriggerOutboxDomainEventPublisher(triggerOutboxEventMapper);
  }

  @Test
  @DisplayName("DomainEvent 字段适配落 trigger_outbox_event 行 — 全字段对齐")
  void mapsDomainEventToEntity() {
    when(triggerOutboxEventMapper.insert(any()))
        .thenAnswer(
            inv -> {
              ((TriggerOutboxEventEntity) inv.getArgument(0)).setId(42L);
              return 1;
            });

    DomainEvent event =
        DomainEvent.builder("tenant-A")
            .aggregate("TRIGGER_REQUEST", 100L)
            .type("trigger.launch.v1")
            .key("req-abc-123")
            .payloadEntry("launchRequest", Map.of("jobCode", "JOB_X"))
            .traceId("trace-xyz")
            .build();

    Long id = publisher.publish(event);

    ArgumentCaptor<TriggerOutboxEventEntity> captor =
        ArgumentCaptor.forClass(TriggerOutboxEventEntity.class);
    verify(triggerOutboxEventMapper).insert(captor.capture());
    TriggerOutboxEventEntity row = captor.getValue();

    assertThat(id).isEqualTo(42L);
    assertThat(row.getTenantId()).isEqualTo("tenant-A");
    assertThat(row.getRequestId()).isEqualTo("req-abc-123");
    assertThat(row.getTopic()).isEqualTo("batch.trigger.launch.v1");
    assertThat(row.getPublishStatus()).isEqualTo(OutboxPublishStatus.NEW.code());
    assertThat(row.getPublishAttempt()).isZero();
    assertThat(row.getTraceId()).isEqualTo("trace-xyz");
    assertThat(row.getNextPublishAt()).isNotNull();
    assertThat(row.getPayload()).contains("launchRequest").contains("JOB_X");
  }
}
