package io.github.pinpols.batch.trigger.infrastructure.mq;

import io.github.pinpols.batch.common.mq.MqMessage;
import io.github.pinpols.batch.common.mq.MqMessagePublisher;
import io.github.pinpols.batch.common.mq.MqPublishResult;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

/** Trigger MQ 发布适配器；业务 relay 只依赖通用消息端口。 */
@Component("triggerMqMessagePublisher")
public class KafkaMqMessagePublisher implements MqMessagePublisher {

  private final KafkaTemplate<String, String> kafkaTemplate;

  public KafkaMqMessagePublisher(
      @Qualifier("triggerKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate) {
    this.kafkaTemplate = kafkaTemplate;
  }

  @Override
  public CompletableFuture<MqPublishResult> publish(MqMessage message) {
    ProducerRecord<String, String> producerRecord =
        new ProducerRecord<>(message.topic(), message.key(), message.payload());
    message.headers().forEach((name, value) -> {
      if (EmptyChecks.isNotNull(value)) {
        byte[] encodedHeaderValue = value.getBytes(StandardCharsets.UTF_8);
        RecordHeader recordHeader = new RecordHeader(name, encodedHeaderValue);
        producerRecord.headers().add(recordHeader);
      }
    });
    CompletableFuture<SendResult<String, String>> sendFuture = kafkaTemplate.send(producerRecord).toCompletableFuture();
    return sendFuture.thenApply(result -> {
      RecordMetadata metadata = result.getRecordMetadata();
      return new MqPublishResult(metadata.partition(), metadata.offset());
    });
  }
}
