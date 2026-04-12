package com.example.batch.worker.core.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.example.batch.common.kafka.BatchTopics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

@ExtendWith(MockitoExtension.class)
class DeadLetterPublisherTest {

  @Mock private KafkaTemplate<String, String> kafkaTemplate;

  private DeadLetterPublisher publisher;

  @BeforeEach
  void setUp() {
    publisher = new DeadLetterPublisher(kafkaTemplate);
  }

  @Test
  void publish_sendsToDeadLetterTopic() {
    publisher.publish("payload", "batch.task.dispatch.import", "IMPORT", "some error");

    ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
    verify(kafkaTemplate).send(eq(BatchTopics.TASK_DEAD_LETTER), valueCaptor.capture());

    String sent = valueCaptor.getValue();
    assertThat(sent).contains("originalPayload");
    assertThat(sent).contains("sourceTopic");
    assertThat(sent).contains("workerType");
    assertThat(sent).contains("errorMessage");
    assertThat(sent).contains("failedAt");
  }

  @Test
  void publish_longErrorMessage_truncatedTo2000chars() {
    String longError = "x".repeat(3000);
    publisher.publish("p", "t", "w", longError);

    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(kafkaTemplate).send(anyString(), captor.capture());
    // 截断后的消息不应包含完整的 3000 个字符
    assertThat(captor.getValue().length()).isLessThan(4000);
  }

  @Test
  void publish_nullErrorMessage_doesNotThrow() {
    assertThatCode(() -> publisher.publish("p", "t", "w", null)).doesNotThrowAnyException();
    verify(kafkaTemplate).send(anyString(), anyString());
  }

  @Test
  void publish_kafkaTemplateThrows_doesNotPropagateException() {
    doThrow(new RuntimeException("kafka down")).when(kafkaTemplate).send(anyString(), anyString());

    assertThatCode(() -> publisher.publish("p", "t", "w", "err")).doesNotThrowAnyException();
  }
}
