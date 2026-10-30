package io.github.pinpols.batch.console.domain.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.pinpols.batch.common.kafka.BatchKafkaTopicNamespace;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.console.domain.notification.service.WorkflowTerminalNotificationService;
import java.time.Duration;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.support.Acknowledgment;

@DisplayName("终态通知 Kafka 接纳:事务先于确认,租户绑定与失败重投")
@ExtendWith(MockitoExtension.class)
class WorkflowTerminalNotificationConsumerTest {
  @Mock
  private WorkflowTerminalNotificationService service;

  @Mock
  private Acknowledgment ack;

  private WorkflowTerminalNotificationConsumer consumer;
  private static final String PAYLOAD = "{\"tenantId\":\"t1\"}";

  @Test
  @DisplayName("Workflow 终态订阅主题与消费组带部署 namespace")
  void shouldNamespaceTopicAndConsumerGroup() {
    WorkflowTerminalNotificationConsumer namespaced =
        new WorkflowTerminalNotificationConsumer(service, new BatchKafkaTopicNamespace("run-a"));

    assertThat(namespaced.topicName()).isEqualTo("run-a.batch.workflow.terminal.v1");
    assertThat(namespaced.consumerGroupId())
        .isEqualTo("run-a.batch-console-workflow-terminal-notifications");
  }

  @Test
  @DisplayName("持久化完成后才确认,并在平台事务前绑定租户且结束后清理")
  void shouldBindTenantAndAcknowledgeAfterPersistence() {
    doAnswer(call -> {
          assertThat(RlsTenantContextHolder.get()).isEqualTo("t1");
          return null;
        })
        .when(service)
        .accept("key", PAYLOAD);
    consumer.consume(record(PAYLOAD), ack);
    var order = inOrder(service, ack);
    order.verify(service).accept("key", PAYLOAD);
    order.verify(ack).acknowledge();
    assertThat(RlsTenantContextHolder.get()).isNull();
  }

  @Test
  @DisplayName("平台库异常保留 offset 并等待重投")
  void shouldRetainOffsetWhenPersistenceFails() {
    doThrow(new DataAccessResourceFailureException("database unavailable"))
        .when(service)
        .accept(any(), any());
    consumer.consume(record(PAYLOAD), ack);
    verify(ack).nack(Duration.ofSeconds(1));
    verify(ack, never()).acknowledge();
    assertThat(RlsTenantContextHolder.get()).isNull();
  }

  @Test
  @DisplayName("缺失租户的报文不产生数据库或外部动作")
  void shouldDropInvalidIdentityWithoutBusinessAction() {
    consumer.consume(record("{}"), ack);
    verifyNoInteractions(service);
    verify(ack).acknowledge();
  }

  private static ConsumerRecord<String, String> record(String payload) {
    return new ConsumerRecord<>("batch.workflow.terminal.v1", 0, 0, "key", payload);
  }

  @BeforeEach
  void initializeSubject() {
    consumer = new WorkflowTerminalNotificationConsumer(service, new BatchKafkaTopicNamespace(""));
  }
}
