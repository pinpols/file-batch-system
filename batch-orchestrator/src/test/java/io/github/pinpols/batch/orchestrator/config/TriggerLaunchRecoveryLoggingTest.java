package io.github.pinpols.batch.orchestrator.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.pinpols.batch.common.dto.LaunchEnvelope;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.trigger.TriggerLaunchRecoveryService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;

@ExtendWith(MockitoExtension.class)
@DisplayName("Trigger耗尽恢复日志敏感参数边界")
class TriggerLaunchRecoveryLoggingTest {
  @Mock
  private TriggerLaunchRecoveryService recoveryService;

  @Mock
  private ObjectProvider<TriggerLaunchRecoveryService> recoveryProvider;

  @Mock
  private Consumer<String, String> consumer;

  @Mock
  private MessageListenerContainer container;

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("耗尽消息只记录定位信息且完整原载荷仍传入持久恢复")
  void shouldKeepRecoveryPayloadOutOfLogs(boolean parked) throws Exception {
    TriggerConsumerProperties properties = new TriggerConsumerProperties();
    properties.getErrorHandler().setRetryAttempts(1);
    properties.getErrorHandler().setRetryBackoffMs(0L);
    UUID identity = UUID.randomUUID();
    String token = identity.toString();
    LaunchRequest request = LaunchRequest.builder()
        .tenantId("log-review-tenant")
        .workflowDefinitionId(1L)
        .bizDate(LocalDate.of(2026, 10, 25))
        .triggerType(TriggerType.MANUAL)
        .requestId("log-review-request")
        .params(Map.of("apiToken", token, "customerBusinessValue", "private-business-content"))
        .build();
    LaunchEnvelope envelope = LaunchEnvelope.of(request, "log-review-key", Instant.EPOCH);
    String payload = JsonUtils.toJson(envelope);
    ConsumerRecord<String, String> record =
        new ConsumerRecord<>(BatchTopics.TRIGGER_LAUNCH_V1, 2, 17L, "log-review-key", payload);
    when(recoveryProvider.getIfAvailable()).thenReturn(recoveryService);
    when(recoveryService.park(payload, null)).thenReturn(parked);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try {
      OrchestratorKafkaConsumerConfiguration configuration =
          new OrchestratorKafkaConsumerConfiguration(properties, registry, recoveryProvider);
      Method factory = OrchestratorKafkaConsumerConfiguration.class.getDeclaredMethod(
          "triggerLaunchErrorHandler");
      factory.setAccessible(true);
      DefaultErrorHandler handler = (DefaultErrorHandler) factory.invoke(configuration);
      Logger logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
      ListAppender<ILoggingEvent> appender = new ListAppender<>();
      appender.start();
      logger.addAppender(appender);
      try {
        IllegalStateException failure = new IllegalStateException("transient database outage");
        boolean handled = handler.handleOne(failure, record, consumer, container);
        assertThat(handled).isEqualTo(parked);
        List<String> messages =
            appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).noneMatch(message -> message.contains(token));
        assertThat(messages).noneMatch(message -> message.contains("private-business-content"));
        assertThat(messages)
            .anySatisfy(message -> assertThat(message)
                .contains(
                    "topic=" + BatchTopics.TRIGGER_LAUNCH_V1,
                    "partition=2",
                    "offset=17",
                    "key=log-review-key",
                    "transient database outage"));
        verify(recoveryService).park(payload, null);
      } finally {
        logger.detachAppender(appender);
        appender.stop();
      }
    } finally {
      registry.close();
    }
  }
}
