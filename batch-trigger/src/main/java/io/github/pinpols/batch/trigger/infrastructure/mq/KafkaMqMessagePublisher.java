package io.github.pinpols.batch.trigger.infrastructure.mq;

import io.github.pinpols.batch.common.mq.MqMessage;
import io.github.pinpols.batch.common.mq.MqMessagePublisher;
import io.github.pinpols.batch.common.mq.MqPublishResult;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Trigger MQ 发布适配器；业务 relay 只依赖通用消息端口。 */
@Component
public class KafkaMqMessagePublisher implements MqMessagePublisher {

  private final KafkaTemplate<String, String> kafkaTemplate;

  public KafkaMqMessagePublisher(
      @Qualifier("triggerKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate) {
    this.kafkaTemplate = kafkaTemplate;
  }

  @Override
  public CompletableFuture<MqPublishResult> publish(MqMessage message) {
    ProducerRecord<String, String> record =
        new ProducerRecord<>(message.topic(), message.key(), message.payload());
    message.headers().forEach((name, value) -> {
      if (EmptyChecks.isNotNull(value)) {
        record.headers().add(new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8)));
      }
    });
    return kafkaTemplate
        .send(record)
        .toCompletableFuture()
        .thenApply(result -> new MqPublishResult(
            result.getRecordMetadata().partition(), result.getRecordMetadata().offset()));
  }
}
