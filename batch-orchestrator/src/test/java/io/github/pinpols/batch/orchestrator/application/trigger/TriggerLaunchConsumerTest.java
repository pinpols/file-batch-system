package io.github.pinpols.batch.orchestrator.application.trigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.LaunchEnvelope;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.kafka.BatchKafkaTopicNamespace;
import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.error.OperationFailure;
import io.github.pinpols.batch.orchestrator.application.error.OperationFailure.Kind;
import io.github.pinpols.batch.orchestrator.application.service.task.LaunchApplicationService;
import io.github.pinpols.batch.orchestrator.config.TriggerConsumerProperties;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.infrastructure.trigger.TriggerLaunchConsumer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

/**
 * ADR-010 Stage 5: TriggerLaunchConsumer 单测,覆盖 9 类路径:
 *
 * <ol>
 *   <li>正常 launch 成功 → ack + counter
 *   <li>反序列化失败 → ack + 跳过(DLQ counter)
 *   <li>envelope/launchRequest 为 null → ack + 跳过
 *   <li>409 冲突未证明启动完成 → 抛出交给监听器重试
 *   <li>429 限流 → nack 当前 offset 并暂停 partition,绕过有限重试丢弃路径
 *   <li>RuntimeException → 抛出走 listener 重试
 *   <li>消费端不重复写 trigger_request，状态仅由 launch 主服务在事务中推进
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("触发启动消费: 报文校验, 去重冲突与限流重投口径")
class TriggerLaunchConsumerTest {

  @Mock
  private LaunchApplicationService launchApplicationService;

  @Mock
  private Acknowledgment ack;

  @Mock
  private OrchestratorGracefulShutdown gracefulShutdown;

  private SimpleMeterRegistry meterRegistry;
  private TriggerLaunchConsumer consumer;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    TriggerConsumerProperties properties = new TriggerConsumerProperties();
    properties.getErrorHandler().setRetryBackoffMs(1234L);
    when(gracefulShutdown.isDraining()).thenReturn(false);
    consumer = new TriggerLaunchConsumer(
        launchApplicationService,
        gracefulShutdown,
        meterRegistry,
        properties,
        new BatchKafkaTopicNamespace(""));
  }

  @Test
  @DisplayName("报文合法时发起启动并确认消息, 累计已消费计数")
  void consume_validEnvelope_launchesAndAcks() {
    LaunchEnvelope envelope = sampleEnvelope("tenant-a", "req-1");
    when(launchApplicationService.launchFromTrustedQueue(any(LaunchRequest.class)))
        .thenReturn(new LaunchResponse("inst-001", "trace-1"));

    consumer.consume(consumerRecord(envelope), ack);

    verify(launchApplicationService, times(1)).launchFromTrustedQueue(any(LaunchRequest.class));
    verify(ack).acknowledge();
    assertThat(consumed("tenant-a", "ok")).isEqualTo(1.0);
    assertThat(
            meterRegistry.find("batch.trigger.launch.consume.duration").timer().count())
        .isEqualTo(1);
  }

  @Test
  @DisplayName("报文格式非法时不发起启动, 确认消息并累计反序列化失败计数")
  void consume_invalidJson_acksAndSkips() {
    ConsumerRecord<String, String> consumerRecord =
        new ConsumerRecord<>(BatchTopics.TRIGGER_LAUNCH_V1, 0, 0L, "key", "{not-json");

    consumer.consume(consumerRecord, ack);

    verify(launchApplicationService, never()).launchFromTrustedQueue(any());
    verify(ack).acknowledge();
    assertThat(failed("deserialize")).isEqualTo(1.0);
  }

  @Test
  @DisplayName("旧版 Envelope 即使包含 Workflow 目标也确认并拒绝，不派发运行")
  void consume_unsupportedEnvelopeVersion_acksAndSkips() {
    LaunchRequest request = sampleEnvelope("tenant-a", "req-old-envelope").launchRequest();
    LaunchEnvelope oldEnvelope = new LaunchEnvelope(
        request,
        "tenant-a:req-old-envelope",
        BatchDateTimeSupport.utcNow(),
        LaunchEnvelope.CURRENT_VERSION - 1,
        null);

    consumer.consume(consumerRecord(oldEnvelope), ack);

    verify(launchApplicationService, never()).launchFromTrustedQueue(any());
    verify(ack).acknowledge();
    assertThat(failed("unsupported_version")).isEqualTo(1.0);
  }

  @Test
  @DisplayName("报文体为空时不发起启动, 确认消息并累计空报文计数")
  void consume_emptyEnvelope_acksAndSkips() {
    ConsumerRecord<String, String> consumerRecord = consumerRecord(null);

    consumer.consume(consumerRecord, ack);

    verify(launchApplicationService, never()).launchFromTrustedQueue(any());
    verify(ack).acknowledge();
    assertThat(failed("empty_envelope")).isEqualTo(1.0);
  }

  @Test
  @DisplayName("事件预期核对冲突不确认消息，重试成功后才确认")
  void consume_expectationConflict_retriesAndAcksOnlyAfterSuccess() {
    LaunchEnvelope envelope = sampleEnvelope("tenant-a", "req-conflict");
    when(launchApplicationService.launchFromTrustedQueue(any(LaunchRequest.class)))
        .thenThrow(new OperationFailure(Kind.CONFLICT, "event expectation conflict"))
        .thenReturn(new LaunchResponse("existing-run", "trace-1"));

    assertThatThrownBy(() -> consumer.consume(consumerRecord(envelope), ack))
        .isInstanceOf(OperationFailure.class)
        .satisfies(error -> assertThat(((OperationFailure) error).kind()).isEqualTo(Kind.CONFLICT));
    verify(ack, never()).acknowledge();
    assertThat(failed("http_409")).isEqualTo(1.0);

    consumer.consume(consumerRecord(envelope), ack);

    verify(ack).acknowledge();
    assertThat(consumed("tenant-a", "ok")).isEqualTo(1.0);
  }

  @Test
  @DisplayName("启动暂不可用时交给监听器重试，不确认消息")
  void consume_serviceUnavailable_propagatesWithoutAcknowledging() {
    LaunchEnvelope envelope = sampleEnvelope("tenant-a", "req-in-progress");
    when(launchApplicationService.launchFromTrustedQueue(any(LaunchRequest.class)))
        .thenThrow(new OperationFailure(Kind.TEMPORARILY_UNAVAILABLE, "retry"));

    assertThatThrownBy(() -> consumer.consume(consumerRecord(envelope), ack))
        .isInstanceOf(OperationFailure.class)
        .satisfies(error ->
            assertThat(((OperationFailure) error).kind()).isEqualTo(Kind.TEMPORARILY_UNAVAILABLE));
    verify(ack, never()).acknowledge();
    assertThat(failed("http_503")).isEqualTo(1.0);
  }

  @ParameterizedTest
  @CsvSource({"INVALID_INPUT, http_400", "NOT_FOUND, http_404", "INVALID_CONFIGURATION, http_422"})
  @DisplayName("其他用例失败保留原诊断标签并交由容器重试")
  void consume_operationFailure_preservesRetryAndMetric(Kind kind, String expectedReason) {
    LaunchEnvelope envelope = sampleEnvelope("tenant-a", "req-failure");
    OperationFailure failure = new OperationFailure(kind, "retryable");
    when(launchApplicationService.launchFromTrustedQueue(any(LaunchRequest.class)))
        .thenThrow(failure);

    assertThatThrownBy(() -> consumer.consume(consumerRecord(envelope), ack)).isSameAs(failure);
    verify(ack, never()).acknowledge();
    assertThat(failed(expectedReason)).isEqualTo(1.0);
  }

  @Test
  @DisplayName("启动被限流时不确认消息, 按延迟重投并累计限流失败计数")
  void consume_rateLimited_doesNotAckSoKafkaCanRedeliver() {
    LaunchEnvelope envelope = sampleEnvelope("tenant-a", "req-rate");
    when(launchApplicationService.launchFromTrustedQueue(any(LaunchRequest.class)))
        .thenThrow(new OperationFailure(Kind.RATE_LIMITED, "rate limit"));

    consumer.consume(consumerRecord(envelope), ack);

    verify(ack, never()).acknowledge();
    verify(ack).nack(Duration.ofMillis(1234L));
    assertThat(failed("rate_limited")).isEqualTo(1.0);
  }

  @Test
  @DisplayName("Orchestrator 排空期间延迟确认 Launch 消息,不创建新作业实例")
  void consume_duringDrainNacksWithoutLaunching() {
    when(gracefulShutdown.isDraining()).thenReturn(true);

    consumer.consume(consumerRecord(sampleEnvelope("tenant-a", "req-drain")), ack);

    verify(launchApplicationService, never()).launchFromTrustedQueue(any());
    verify(ack, never()).acknowledge();
    verify(ack).nack(Duration.ofMillis(1234L));
    assertThat(meterRegistry.counter("batch.trigger.launch.deferred.total").count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("启动抛运行时异常时向监听器传播以便重试, 不确认消息")
  void consume_runtimeException_propagatesForListenerRetry() {
    LaunchEnvelope envelope = sampleEnvelope("tenant-a", "req-err");
    when(launchApplicationService.launchFromTrustedQueue(any(LaunchRequest.class)))
        .thenThrow(new IllegalStateException("downstream down"));

    assertThatThrownBy(() -> consumer.consume(consumerRecord(envelope), ack))
        .isInstanceOf(IllegalStateException.class);

    verify(ack, never()).acknowledge();
    assertThat(failed("runtime")).isEqualTo(1.0);
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private static LaunchEnvelope sampleEnvelope(String tenantId, String requestId) {
    LaunchRequest request = new LaunchRequest(
        tenantId,
        "test-job",
        LocalDate.parse("2026-04-30"),
        TriggerType.MANUAL,
        requestId,
        "trace-" + requestId,
        Map.of());
    return LaunchEnvelope.of(request, tenantId + ":" + requestId, BatchDateTimeSupport.utcNow());
  }

  private static ConsumerRecord<String, String> consumerRecord(LaunchEnvelope envelope) {
    String value = envelope == null ? "null" : JsonUtils.toJson(envelope);
    return new ConsumerRecord<>(BatchTopics.TRIGGER_LAUNCH_V1, 0, 0L, "key", value);
  }

  private double consumed(String tenant, String outcome) {
    return meterRegistry
        .counter("batch.trigger.launch.consumed.total", "tenant", tenant, "outcome", outcome)
        .count();
  }

  private double failed(String reason) {
    // R3-P0-11 后，部分失败路径（rate_limited / http_* / runtime）counter 同时带 tenant + reason；
    // 早期路径（deserialize / empty_envelope）只带 reason。Mockito 测试需聚合两种命名下的同 reason series。
    return meterRegistry
        .find("batch.trigger.launch.failed.total")
        .tag("reason", reason)
        .counters()
        .stream()
        .mapToDouble(Counter::count)
        .sum();
  }
}
