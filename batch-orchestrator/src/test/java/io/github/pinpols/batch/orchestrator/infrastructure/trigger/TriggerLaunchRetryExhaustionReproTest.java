package io.github.pinpols.batch.orchestrator.infrastructure.trigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.Acknowledgment;

/**
 * 复现「Kafka 重试耗尽后的恢复」缺口（只加测试，不改主代码）。
 *
 * <p>命题：一个尚未落持久等待记录（{@code batch_day_waiting_launch} / 已提交 prepare）的触发请求，在消费端瞬态失败
 * 重试耗尽后，是否会失去后续执行机会。
 *
 * <p>预期（正确行为）：重试耗尽的最后一次处置必须留下可恢复锚点 —— 要么把记录重投到重试/DLQ 通道，要么把请求持久化为
 * 等待记录供周期扫描恢复。
 *
 * <p>当前实现的静态事实：生产 recoverer 只做日志 + 指标（无 KafkaTemplate、无 DAO），{@link FixedBackOff}
 * 在耗尽后不再 seek，容器直接提交 offset。因此该测试在修复前应当是**红**的。
 */
@DisplayName("复现: launch 瞬态失败在 Kafka 有限重试耗尽后失去执行机会")
class TriggerLaunchRetryExhaustionReproTest {

  @Test
  @DisplayName("重试耗尽时必须留下可恢复锚点(重投或持久等待)，否则请求永久丢失")
  void shouldKeepDurableRecoveryAnchor_whenRetryExhausted() throws Exception {
    TriggerConsumerProperties properties = new TriggerConsumerProperties();
    properties.getErrorHandler().setRetryBackoffMs(0L);
    properties.getErrorHandler().setRetryAttempts(2);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    LaunchApplicationService launchService = mock(LaunchApplicationService.class);
    when(launchService.launchFromTrustedQueue(any()))
        .thenThrow(
            new IllegalStateException("transient db failure; prepare transaction rolled back"));
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
        .isInstanceOf(IllegalStateException.class);

    // 取生产 error handler（反射，不新增/不修改生产代码）
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
    boolean firstAttemptHandled =
        handler.handleOne(attemptFailure, record, kafkaConsumer, container);
    boolean exhaustedAttemptHandled =
        handler.handleOne(attemptFailure, record, kafkaConsumer, container);

    boolean redeliveredAtExhaustion = !exhaustedAttemptHandled;

    // 静态事实：生产 recoverer 与该配置类不持有任何持久化/重投能力（只有 properties + meterRegistry）
    List<String> declaredFieldTypes = Arrays.stream(
            OrchestratorKafkaConsumerConfiguration.class.getDeclaredFields())
        .map(Field::getType)
        .map(Class::getSimpleName)
        .toList();
    boolean handlerCanPersistOrRepublish = declaredFieldTypes.stream()
        .anyMatch(type -> type.contains("KafkaTemplate")
            || type.contains("Mapper")
            || type.contains("Repository"));

    double exhaustedCount = meterRegistry
        .find("batch.trigger.launch.failed.total")
        .tag("reason", "retries_exhausted")
        .counter()
        .count();

    Mockito.verify(recoveryService).park(JsonUtils.toJson(envelope), null);
    boolean recoveryParked = true;
    assertThat(redeliveredAtExhaustion || handlerCanPersistOrRepublish || recoveryParked)
        .as(
            "重试耗尽后必须留下可恢复锚点: 记录被跳过(firstHandled=%s, exhaustedHandled=%s)且 recoverer 无持久化/重投能力 →"
                + " 未落等待记录的请求永久失去执行机会; retries_exhausted=%s, 配置类字段=%s",
            firstAttemptHandled, exhaustedAttemptHandled, exhaustedCount, declaredFieldTypes)
        .isTrue();
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
