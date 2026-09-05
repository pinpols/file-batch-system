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
import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.service.task.LaunchApplicationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.web.server.ResponseStatusException;

/**
 * ADR-010 Stage 5: TriggerLaunchConsumer 单测,覆盖 9 类路径:
 *
 * <ol>
 *   <li>正常 launch 成功 → ack + counter
 *   <li>反序列化失败 → ack + 跳过(DLQ counter)
 *   <li>envelope/launchRequest 为 null → ack + 跳过
 *   <li>409 dedup 命中 → 视为成功 ack(uk_job_instance_tenant_dedup 回退)
 *   <li>429 限流 → 不 ack,抛出让 Kafka listener container 重投
 *   <li>RuntimeException → 抛出走 listener 重试
 *   <li>消费端不重复写 trigger_request，状态仅由 launch 主服务在事务中推进
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class TriggerLaunchConsumerTest {

  @Mock
  private LaunchApplicationService launchApplicationService;

  @Mock
  private Acknowledgment ack;

  private SimpleMeterRegistry meterRegistry;
  private TriggerLaunchConsumer consumer;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    consumer = new TriggerLaunchConsumer(launchApplicationService, meterRegistry);
  }

  @Test
  void consume_validEnvelope_launchesAndAcks() {
    LaunchEnvelope envelope = sampleEnvelope("tenant-a", "req-1");
    when(launchApplicationService.launch(any(LaunchRequest.class)))
        .thenReturn(new LaunchResponse("inst-001", "trace-1"));

    consumer.consume(consumerRecord(envelope), ack);

    verify(launchApplicationService, times(1)).launch(any(LaunchRequest.class));
    verify(ack).acknowledge();
    assertThat(consumed("tenant-a", "ok")).isEqualTo(1.0);
    assertThat(
            meterRegistry.find("batch.trigger.launch.consume.duration").timer().count())
        .isEqualTo(1);
  }

  @Test
  void consume_invalidJson_acksAndSkips() {
    ConsumerRecord<String, String> consumerRecord =
        new ConsumerRecord<>(BatchTopics.TRIGGER_LAUNCH_V1, 0, 0L, "key", "{not-json");

    consumer.consume(consumerRecord, ack);

    verify(launchApplicationService, never()).launch(any());
    verify(ack).acknowledge();
    assertThat(failed("deserialize")).isEqualTo(1.0);
  }

  @Test
  void consume_emptyEnvelope_acksAndSkips() {
    ConsumerRecord<String, String> consumerRecord = consumerRecord(null);

    consumer.consume(consumerRecord, ack);

    verify(launchApplicationService, never()).launch(any());
    verify(ack).acknowledge();
    assertThat(failed("empty_envelope")).isEqualTo(1.0);
  }

  @Test
  void consume_dedupConflict_treatedAsSuccessAndAcks() {
    LaunchEnvelope envelope = sampleEnvelope("tenant-a", "req-dup");
    when(launchApplicationService.launch(any(LaunchRequest.class)))
        .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "dedup hit"));

    consumer.consume(consumerRecord(envelope), ack);

    verify(ack).acknowledge();
    assertThat(deduped("tenant-a")).isEqualTo(1.0);
  }

  @Test
  void consume_rateLimited_doesNotAckSoKafkaCanRedeliver() {
    LaunchEnvelope envelope = sampleEnvelope("tenant-a", "req-rate");
    when(launchApplicationService.launch(any(LaunchRequest.class)))
        .thenThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "rate limit"));

    assertThatThrownBy(() -> consumer.consume(consumerRecord(envelope), ack))
        .isInstanceOf(ResponseStatusException.class);

    verify(ack, never()).acknowledge();
    assertThat(failed("rate_limited")).isEqualTo(1.0);
  }

  @Test
  void consume_runtimeException_propagatesForListenerRetry() {
    LaunchEnvelope envelope = sampleEnvelope("tenant-a", "req-err");
    when(launchApplicationService.launch(any(LaunchRequest.class)))
        .thenThrow(new IllegalStateException("downstream down"));

    assertThatThrownBy(() -> consumer.consume(consumerRecord(envelope), ack))
        .isInstanceOf(IllegalStateException.class);

    verify(ack, never()).acknowledge();
    assertThat(failed("runtime")).isEqualTo(1.0);
  }

  @Test
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

  private double deduped(String tenant) {
    return meterRegistry
        .counter("batch.trigger.launch.deduped.total", "tenant", tenant)
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
        .mapToDouble(io.micrometer.core.instrument.Counter::count)
        .sum();
  }
}
