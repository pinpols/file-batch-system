package io.github.pinpols.batch.worker.core.infrastructure;

import io.github.pinpols.batch.common.mq.MqMessage;
import io.github.pinpols.batch.common.mq.MqMessagePublisher;
import io.github.pinpols.batch.common.mq.MqPublishResult;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Worker MQ 发布适配器；DLQ 等业务组件不直接依赖 KafkaTemplate。 */
@Component("workerMqMessagePublisher")
public class KafkaMqMessagePublisher implements MqMessagePublisher {

  private final KafkaTemplate<String, String> kafkaTemplate;

  public KafkaMqMessagePublisher(KafkaTemplate<String, String> kafkaTemplate) {
    this.kafkaTemplate = kafkaTemplate;
  }

  @Override
  public CompletableFuture<MqPublishResult> publish(MqMessage message) {
    ProducerRecord<String, String> producerRecord =
        new ProducerRecord<>(message.topic(), message.key(), message.payload());
    message.headers().forEach((name, value) -> {
      if (EmptyChecks.isNotNull(value)) {
        producerRecord
            .headers()
            .add(new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8)));
      }
    });
    return kafkaTemplate
        .send(producerRecord)
        .toCompletableFuture()
        .thenApply(result -> new MqPublishResult(
            result.getRecordMetadata().partition(), result.getRecordMetadata().offset()));
  }
}
