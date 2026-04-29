package com.example.batch.orchestrator.application.trigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.dto.LaunchEnvelope;
import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.application.service.LaunchApplicationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
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
 * ADR-010 Stage 5: TriggerLaunchConsumer 单测,覆盖 6 类路径:
 *
 * <ol>
 *   <li>正常 launch 成功 → ack + counter
 *   <li>反序列化失败 → ack + 跳过(DLQ counter)
 *   <li>envelope/launchRequest 为 null → ack + 跳过
 *   <li>409 dedup 命中 → 视为成功 ack(uk_job_instance_tenant_dedup 兜底)
 *   <li>429 限流 → ack + 由 trigger outbox 重发
 *   <li>RuntimeException → 抛出走 listener 重试
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class TriggerLaunchConsumerTest {

  @Mock private LaunchApplicationService launchApplicationService;
  @Mock private Acknowledgment ack;

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

    consumer.consume(record(envelope), ack);

    verify(launchApplicationService, times(1)).launch(any(LaunchRequest.class));
    verify(ack).acknowledge();
    assertThat(consumed("tenant-a", "ok")).isEqualTo(1.0);
  }

  @Test
  void consume_invalidJson_acksAndSkips() {
    ConsumerRecord<String, String> record =
        new ConsumerRecord<>("batch.trigger.launch.v1", 0, 0L, "key", "{not-json");

    consumer.consume(record, ack);

    verify(launchApplicationService, never()).launch(any());
    verify(ack).acknowledge();
    assertThat(failed("deserialize")).isEqualTo(1.0);
  }

  @Test
  void consume_emptyEnvelope_acksAndSkips() {
    ConsumerRecord<String, String> record = record(null);

    consumer.consume(record, ack);

    verify(launchApplicationService, never()).launch(any());
    verify(ack).acknowledge();
    assertThat(failed("empty_envelope")).isEqualTo(1.0);
  }

  @Test
  void consume_dedupConflict_treatedAsSuccessAndAcks() {
    LaunchEnvelope envelope = sampleEnvelope("tenant-a", "req-dup");
    when(launchApplicationService.launch(any(LaunchRequest.class)))
        .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "dedup hit"));

    consumer.consume(record(envelope), ack);

    verify(ack).acknowledge();
    assertThat(deduped("tenant-a")).isEqualTo(1.0);
  }

  @Test
  void consume_rateLimited_acksAndSkipsToAllowOutboxRetry() {
    LaunchEnvelope envelope = sampleEnvelope("tenant-a", "req-rate");
    when(launchApplicationService.launch(any(LaunchRequest.class)))
        .thenThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "rate limit"));

    consumer.consume(record(envelope), ack);

    verify(ack).acknowledge();
    assertThat(failed("rate_limited")).isEqualTo(1.0);
  }

  @Test
  void consume_runtimeException_propagatesForListenerRetry() {
    LaunchEnvelope envelope = sampleEnvelope("tenant-a", "req-err");
    when(launchApplicationService.launch(any(LaunchRequest.class)))
        .thenThrow(new IllegalStateException("downstream down"));

    assertThatThrownBy(() -> consumer.consume(record(envelope), ack))
        .isInstanceOf(IllegalStateException.class);

    verify(ack, never()).acknowledge();
    assertThat(failed("runtime")).isEqualTo(1.0);
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private static LaunchEnvelope sampleEnvelope(String tenantId, String requestId) {
    LaunchRequest request =
        new LaunchRequest(
            tenantId,
            "test-job",
            LocalDate.parse("2026-04-30"),
            TriggerType.MANUAL,
            requestId,
            "trace-" + requestId,
            Map.of());
    return LaunchEnvelope.of(request, tenantId + ":" + requestId, Instant.now());
  }

  private static ConsumerRecord<String, String> record(LaunchEnvelope envelope) {
    String value = envelope == null ? "null" : JsonUtils.toJson(envelope);
    return new ConsumerRecord<>("batch.trigger.launch.v1", 0, 0L, "key", value);
  }

  private double consumed(String tenant, String outcome) {
    return meterRegistry
        .counter("batch.trigger.launch.consumed.total", "tenant", tenant, "outcome", outcome)
        .count();
  }

  private double deduped(String tenant) {
    return meterRegistry.counter("batch.trigger.launch.deduped.total", "tenant", tenant).count();
  }

  private double failed(String reason) {
    return meterRegistry.counter("batch.trigger.launch.failed.total", "reason", reason).count();
  }
}
