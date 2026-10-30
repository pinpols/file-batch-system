package io.github.pinpols.batch.trigger.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.OutboxPublishStatus;
import io.github.pinpols.batch.common.event.DomainEvent;
import io.github.pinpols.batch.common.kafka.BatchKafkaTopicNamespace;
import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.common.persistence.entity.TriggerOutboxEventEntity;
import io.github.pinpols.batch.trigger.mapper.TriggerOutboxEventMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 守护 TriggerOutboxDomainEventPublisher 把 DomainEvent 准确投影到 trigger_outbox_event 行的适配语义。
 *
 * <p>Propagation.MANDATORY 守护(无事务时抛 IllegalTransactionStateException)由 Spring 容器在 集成测中验证 — 单测直接调
 * publisher 不走 AOP,断言意义不大,放 IT 层覆盖。
 */
@DisplayName("TriggerOutboxDomainEventPublisher:DomainEvent 到 trigger_outbox_event 行的字段投影与落库适配语义")
@ExtendWith(MockitoExtension.class)
class TriggerOutboxDomainEventPublisherTest {

  @Mock
  private TriggerOutboxEventMapper triggerOutboxEventMapper;

  private TriggerOutboxDomainEventPublisher publisher;

  @BeforeEach
  void setUp() {
    publisher = new TriggerOutboxDomainEventPublisher(
        triggerOutboxEventMapper, new BatchKafkaTopicNamespace(""));
  }

  @Test
  @DisplayName("DomainEvent 字段适配落 trigger_outbox_event 行 — 全字段对齐")
  void domainEvent_mapsToEntity() {
    when(triggerOutboxEventMapper.insert(any())).thenAnswer(inv -> {
      ((TriggerOutboxEventEntity) inv.getArgument(0)).setId(42L);
      return 1;
    });

    DomainEvent event = DomainEvent.builder("tenant-A")
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
    assertThat(row.getTopic()).isEqualTo(BatchTopics.TRIGGER_LAUNCH_V1);
    assertThat(row.getPublishStatus()).isEqualTo(OutboxPublishStatus.NEW.code());
    assertThat(row.getPublishAttempt()).isZero();
    assertThat(row.getTraceId()).isEqualTo("trace-xyz");
    assertThat(row.getNextPublishAt()).isNotNull();
    assertThat(row.getPayload()).contains("launchRequest").contains("JOB_X");
  }

  @Test
  @DisplayName("Trigger outbox 持久化部署命名空间下的 launch topic")
  void publish_usesDeploymentNamespacedTopic() {
    publisher = new TriggerOutboxDomainEventPublisher(
        triggerOutboxEventMapper, new BatchKafkaTopicNamespace("workflow-east-1"));

    publisher.publish(DomainEvent.builder("tenant-A")
        .aggregate("TRIGGER_REQUEST", 101L)
        .type("trigger.launch.v1")
        .key("req-namespaced")
        .payloadEntry("launchRequest", Map.of("jobCode", "JOB_X"))
        .traceId("trace-namespaced")
        .build());

    ArgumentCaptor<TriggerOutboxEventEntity> captor =
        ArgumentCaptor.forClass(TriggerOutboxEventEntity.class);
    verify(triggerOutboxEventMapper).insert(captor.capture());
    assertThat(captor.getValue().getTopic())
        .isEqualTo("workflow-east-1." + BatchTopics.TRIGGER_LAUNCH_V1);
  }
}
