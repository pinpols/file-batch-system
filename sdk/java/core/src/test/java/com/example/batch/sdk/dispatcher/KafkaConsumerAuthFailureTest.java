package com.example.batch.sdk.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.sdk.client.BatchPlatformClientConfig;
import com.example.batch.sdk.client.BatchSdkClientException;
import com.example.batch.sdk.internal.PlatformHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.common.errors.SaslAuthenticationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Lane E #4-Java:Kafka SASL 凭据错 → poll loop 必须 fail-fast,置 {@code fatalAuthFailure=true} + {@code
 * running=false} + 抛 RuntimeException,让 Pod 重启;不重试。
 */
class KafkaConsumerAuthFailureTest {

  private final BatchPlatformClientConfig config =
      BatchPlatformClientConfig.builder()
          .baseUrl("http://localhost:0")
          .tenantId("tx")
          .workerCode("w-1")
          .kafkaBootstrap("kafka:9092")
          .kafkaTopicPattern("batch.task.dispatch.tx.*")
          .kafkaGroupId("g")
          .maxConcurrentTasks(2)
          .build();

  private TaskDispatcher dispatcher;

  @AfterEach
  void tearDown() {
    if (dispatcher != null) dispatcher.stop();
  }

  @SuppressWarnings("unchecked")
  private Consumer<String, byte[]> mockConsumer() {
    return mock(Consumer.class);
  }

  @Test
  void authenticationExceptionTriggersFailFast() throws Exception {
    dispatcher = new TaskDispatcher(config, Map.of(), mock(PlatformHttpClient.class));
    Consumer<String, byte[]> consumer = mockConsumer();
    doNothing().when(consumer).subscribe(any(Pattern.class), any(ConsumerRebalanceListener.class));
    when(consumer.assignment()).thenReturn(Set.of());
    when(consumer.poll(any())).thenThrow(new SaslAuthenticationException("bad creds"));

    try (KafkaTaskConsumer kafka =
        new KafkaTaskConsumer(config, dispatcher, consumer, new ObjectMapper())) {
      // 执行并断言:run() 抛 BatchSdkClientException(stage=KAFKA_AUTH),SASL fail-fast
      assertThatThrownBy(kafka::run)
          .isInstanceOf(BatchSdkClientException.class)
          .hasMessageContaining("SASL auth failed")
          .hasCauseInstanceOf(SaslAuthenticationException.class)
          .extracting(t -> ((BatchSdkClientException) t).stage())
          .isEqualTo(BatchSdkClientException.Stage.KAFKA_AUTH);

      assertThat(kafka.isFatalAuthFailure()).isTrue();
      assertThat(kafka.isRunning()).isFalse();
      // crashed 不该被置 —— auth 失败是确定性的 fatal,不归入"非预期 Throwable"
      assertThat(kafka.hasCrashed()).isFalse();
    }
  }
}
