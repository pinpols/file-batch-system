package io.github.pinpols.batch.orchestrator.infrastructure.trigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.LaunchEnvelope;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.kafka.BatchKafkaTopicNamespace;
import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.service.task.LaunchApplicationService;
import io.github.pinpols.batch.orchestrator.application.trigger.TriggerLaunchRecoveryService;
import io.github.pinpols.batch.orchestrator.config.OrchestratorKafkaConsumerConfiguration;
import io.github.pinpols.batch.orchestrator.config.TriggerConsumerProperties;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.Acknowledgment;
import io.github.pinpols.batch.orchestrator.application.error.OperationFailure;
import io.github.pinpols.batch.orchestrator.application.error.OperationFailure.Kind;

/**
 * 验证 Kafka 启动消息在有限重试耗尽后的恢复处置。
 *
 * <p>recoverer 必须先成功登记原始 envelope 的持久恢复锚点，才能将记录标记为已处置；登记失败时仍需重投。
 */
@DisplayName("启动消息重试耗尽后保留持久恢复锚点")
class TriggerLaunchRetryExhaustionReproTest {

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("瞬态异常或预期核对冲突重试耗尽时必须留下持久恢复锚点")
  void shouldKeepDurableRecoveryAnchor_whenRetryExhausted(boolean conflict) throws Exception {
    TriggerConsumerProperties properties = new TriggerConsumerProperties();
    properties.getErrorHandler().setRetryBackoffMs(0L);
    properties.getErrorHandler().setRetryAttempts(2);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    LaunchApplicationService launchService = mock(LaunchApplicationService.class);
    RuntimeException failure = conflict
        ? new OperationFailure(
            Kind.CONFLICT, "event expectation conflict", new IllegalArgumentException("cause"))
        : new IllegalStateException("transient db failure; prepare transaction rolled back");
    when(launchService.launchFromTrustedQueue(any())).thenThrow(failure);
    OrchestratorGracefulShutdown gracefulShutdown = mock(OrchestratorGracefulShutdown.class);
    when(gracefulShutdown.isDraining()).thenReturn(false);

    TriggerLaunchConsumer consumer = new TriggerLaunchConsumer(
        launchService,
        gracefulShutdown,
        meterRegistry,
        properties,
        new BatchKafkaTopicNamespace(""));

    LaunchRequest request = new LaunchRequest(
        "tenant-a",
        "test-job",
        LocalDate.parse("2026-10-24"),
        TriggerType.MANUAL,
        "req-retry-exhausted",
        "trace-retry-exhausted",
        Map.of());
    LaunchEnvelope envelope =
        LaunchEnvelope.of(request, "tenant-a:req-retry-exhausted", BatchDateTimeSupport.utcNow());
    ConsumerRecord<String, String> record = new ConsumerRecord<>(
        BatchTopics.TRIGGER_LAUNCH_V1, 0, 0L, "key", JsonUtils.toJson(envelope));

    Acknowledgment ack = mock(Acknowledgment.class);
    Throwable listenerFailure = catchThrowable(() -> consumer.consume(record, ack));
    assertThat(listenerFailure)
        .as("listener 必须把瞬态失败抛回容器，交给 error handler 重试")
        .isSameAs(failure);
    verify(ack, never()).acknowledge();

    // 使用实际装配的生产 error handler。
    @SuppressWarnings("unchecked")
    ObjectProvider<TriggerLaunchRecoveryService> recoveryProvider = mock(ObjectProvider.class);
    TriggerLaunchRecoveryService recoveryService = mock(TriggerLaunchRecoveryService.class);
    when(recoveryProvider.getIfAvailable()).thenReturn(recoveryService);
    when(recoveryService.park(JsonUtils.toJson(envelope), null)).thenReturn(true);
    OrchestratorKafkaConsumerConfiguration configuration =
        new OrchestratorKafkaConsumerConfiguration(properties, meterRegistry, recoveryProvider);
    Method factory =
        OrchestratorKafkaConsumerConfiguration.class.getDeclaredMethod("triggerLaunchErrorHandler");
    factory.setAccessible(true);
    DefaultErrorHandler handler = (DefaultErrorHandler) factory.invoke(configuration);

    @SuppressWarnings("unchecked")
    Consumer<String, String> kafkaConsumer = mock(Consumer.class);
    MessageListenerContainer container = mock(MessageListenerContainer.class);
    when(container.isRunning()).thenReturn(true);
    when(container.getContainerProperties())
        .thenReturn(new ContainerProperties(BatchTopics.TRIGGER_LAUNCH_V1));
    Exception attemptFailure = (Exception) listenerFailure;

    // Spring Kafka 4.x: handleOne 返回 false = 仍需重投(容器 seek 后重投)，true = 已处置(跳过并提交 offset)
    assertThat(handler.handleOne(attemptFailure, record, kafkaConsumer, container))
        .as("首次失败应继续重投，不能提前处置")
        .isFalse();
    verifyNoInteractions(recoveryService);

    assertThat(handler.handleOne(attemptFailure, record, kafkaConsumer, container))
        .as("重试耗尽后登记恢复锚点成功，才标记为已处置")
        .isTrue();
    verify(recoveryService).park(JsonUtils.toJson(envelope), null);

    double exhaustedCount = meterRegistry
        .find("batch.trigger.launch.failed.total")
        .tag("reason", "retries_exhausted")
        .counter()
        .count();

    assertThat(exhaustedCount).isEqualTo(1.0);
  }

  @Test
  @DisplayName("持久恢复锚点写入失败时不得确认耗尽消息")
  void shouldNotCompleteRecovery_whenDurableAnchorCannotBeWritten() throws Exception {
    TriggerConsumerProperties properties = new TriggerConsumerProperties();
    properties.getErrorHandler().setRetryBackoffMs(0L);
    properties.getErrorHandler().setRetryAttempts(2);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    TriggerLaunchRecoveryService recoveryService = mock(TriggerLaunchRecoveryService.class);
    when(recoveryService.park(any(), any())).thenReturn(false);
    @SuppressWarnings("unchecked")
    ObjectProvider<TriggerLaunchRecoveryService> recoveryProvider = mock(ObjectProvider.class);
    when(recoveryProvider.getIfAvailable()).thenReturn(recoveryService);
    OrchestratorKafkaConsumerConfiguration configuration =
        new OrchestratorKafkaConsumerConfiguration(properties, meterRegistry, recoveryProvider);
    Method factory =
        OrchestratorKafkaConsumerConfiguration.class.getDeclaredMethod("triggerLaunchErrorHandler");
    factory.setAccessible(true);
    DefaultErrorHandler handler = (DefaultErrorHandler) factory.invoke(configuration);

    ConsumerRecord<String, String> record =
        new ConsumerRecord<>(BatchTopics.TRIGGER_LAUNCH_V1, 0, 1L, "key", "valid-envelope-json");
    @SuppressWarnings("unchecked")
    Consumer<String, String> kafkaConsumer = mock(Consumer.class);
    MessageListenerContainer container = mock(MessageListenerContainer.class);
    when(container.isRunning()).thenReturn(true);
    when(container.getContainerProperties())
        .thenReturn(new ContainerProperties(BatchTopics.TRIGGER_LAUNCH_V1));
    IllegalStateException listenerFailure = new IllegalStateException("transient database failure");

    assertThat(handler.handleOne(listenerFailure, record, kafkaConsumer, container))
        .as("首次失败仍应安排有限重试")
        .isFalse();
    assertThat(handler.handleOne(listenerFailure, record, kafkaConsumer, container))
        .as("若没有持久恢复锚点，error handler 不能把 offset 当作已恢复并跳过")
        .isFalse();
    verify(recoveryService).park("valid-envelope-json", null);
  }
}
