package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.worker.dispatchs.config.DispatchCircuitBreakerProperties;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.DispatchDeliveryMetrics;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DispatchChannelGatewayTest {

  private DispatchChannelAdapter httpAdapter;
  private DispatchChannelCircuitBreaker circuitBreaker;
  private DispatchDeliveryMetrics deliveryMetrics;
  private DispatchChannelHealthService healthService;
  private DispatchChannelGateway gateway;

  @BeforeEach
  void setUp() {
    httpAdapter = mock(DispatchChannelAdapter.class);
    when(httpAdapter.supports("API")).thenReturn(true);
    when(httpAdapter.supports("SFTP")).thenReturn(false);

    // use real circuit breaker with lenient threshold
    DispatchCircuitBreakerProperties cbProps = new DispatchCircuitBreakerProperties();
    cbProps.setEnabled(true);
    cbProps.setFailureThreshold(5);
    cbProps.setCooldownMillis(60_000L);
    circuitBreaker = new DispatchChannelCircuitBreaker(cbProps);

    deliveryMetrics = mock(DispatchDeliveryMetrics.class);
    healthService = mock(DispatchChannelHealthService.class);
    when(healthService.allowDispatch(any())).thenReturn(true);

    gateway =
        new DispatchChannelGateway(
            List.of(httpAdapter), circuitBreaker, deliveryMetrics, healthService);
  }

  @Test
  void shouldDispatchSuccessfullyViaMatchingAdapter() {
    DispatchResult success = new DispatchResult(true, "req-1", null, true, false, "ok", null);
    when(httpAdapter.dispatch(any())).thenReturn(success);

    DispatchResult result = gateway.dispatch(command("t1", "API", "ch-1"));

    assertThat(result.success()).isTrue();
    verify(deliveryMetrics).recordDelivery("API", true, false);
  }

  @Test
  void shouldRecordCircuitBreakerFailureOnAdapterFailure() {
    DispatchResult failure = new DispatchResult(false, null, null, false, false, "timeout", null);
    when(httpAdapter.dispatch(any())).thenReturn(failure);

    DispatchResult result = gateway.dispatch(command("t1", "API", "ch-1"));

    assertThat(result.success()).isFalse();
    verify(deliveryMetrics).recordDelivery("API", false, false);
  }

  @Test
  void shouldBlockWhenHealthServiceRejectsDispatch() {
    when(healthService.allowDispatch(any())).thenReturn(false);

    DispatchResult result = gateway.dispatch(command("t1", "API", "ch-1"));

    assertThat(result.success()).isFalse();
    assertThat(result.message()).contains("health backoff");
    verify(httpAdapter, never()).dispatch(any());
    verify(deliveryMetrics).recordDelivery("API", false, true);
  }

  @Test
  void shouldBlockWhenCircuitIsOpen() {
    // trigger the circuit open via real circuit breaker
    DispatchCircuitBreakerProperties cbProps = new DispatchCircuitBreakerProperties();
    cbProps.setEnabled(true);
    cbProps.setFailureThreshold(3);
    cbProps.setCooldownMillis(60_000L);
    DispatchChannelCircuitBreaker shortBreaker = new DispatchChannelCircuitBreaker(cbProps);
    for (int i = 0; i < 3; i++) {
      shortBreaker.recordFailure("t1|API|ch-1");
    }

    DispatchChannelGateway gatewayWithOpenCircuit =
        new DispatchChannelGateway(
            List.of(httpAdapter), shortBreaker, deliveryMetrics, healthService);

    DispatchResult result = gatewayWithOpenCircuit.dispatch(command("t1", "API", "ch-1"));

    assertThat(result.success()).isFalse();
    assertThat(result.message()).contains("circuit open");
    verify(httpAdapter, never()).dispatch(any());
    verify(deliveryMetrics).recordDelivery("API", false, true);
  }

  @Test
  void shouldThrowWhenNoAdapterSupportsChannelType() {
    assertThatThrownBy(() -> gateway.dispatch(command("t1", "SFTP", "ch-1")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unsupported channel type: SFTP");
  }

  // --- I-1 许可泄漏兜底:allow() 后逃逸异常必须配对释放熔断许可,否则 HALF_OPEN 永久 brick ---

  @Test
  void shouldReleasePermitWhenAdapterDispatchThrows() {
    DispatchChannelCircuitBreaker cb = mock(DispatchChannelCircuitBreaker.class);
    when(cb.allow(anyString())).thenReturn(true);
    when(httpAdapter.dispatch(any())).thenThrow(new RuntimeException("adapter boom"));
    DispatchChannelGateway g =
        new DispatchChannelGateway(List.of(httpAdapter), cb, deliveryMetrics, healthService);

    assertThatThrownBy(() -> g.dispatch(command("t1", "API", "ch-1")))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("adapter boom");
    // 逃逸路径必须 recordFailure(= R4J onError 释放许可),否则许可泄漏
    verify(cb).recordFailure("t1|API|ch-1");
  }

  @Test
  void shouldReleasePermitWhenAdapterResolutionThrows() {
    DispatchChannelCircuitBreaker cb = mock(DispatchChannelCircuitBreaker.class);
    when(cb.allow(anyString())).thenReturn(true);
    // SFTP 归一化为官方渠道但无 adapter 支持 → resolveAdapter 抛 IllegalStateException(在 allow 之后)
    DispatchChannelGateway g =
        new DispatchChannelGateway(List.of(httpAdapter), cb, deliveryMetrics, healthService);

    assertThatThrownBy(() -> g.dispatch(command("t1", "SFTP", "ch-1")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unsupported channel type: SFTP");
    verify(cb).recordFailure("t1|SFTP|ch-1");
  }

  @Test
  void shouldNotPermanentlyBrickKeyWhenHalfOpenProbeThrows() throws InterruptedException {
    // 真实 breaker 端到端:HALF_OPEN 探测抛异常若不释放许可,该 key 会永久卡半开;验证释放后可恢复。
    DispatchCircuitBreakerProperties cbProps = new DispatchCircuitBreakerProperties();
    cbProps.setEnabled(true);
    cbProps.setFailureThreshold(3); // = HALF_OPEN 探测预算
    cbProps.setCooldownMillis(30L);
    DispatchChannelCircuitBreaker realCb = new DispatchChannelCircuitBreaker(cbProps);
    for (int i = 0; i < 3; i++) {
      realCb.recordFailure("t1|API|ch-1"); // → OPEN
    }
    assertThat(realCb.allow("t1|API|ch-1")).isFalse();
    Thread.sleep(60); // → HALF_OPEN 可探测

    DispatchChannelGateway g =
        new DispatchChannelGateway(List.of(httpAdapter), realCb, deliveryMetrics, healthService);
    when(httpAdapter.dispatch(any())).thenThrow(new RuntimeException("probe boom"));

    // 耗尽全部 3 个半开探测,每次都抛异常。若许可泄漏(无兜底),3 次后 breaker 永久卡半开(0 许可、0 完成、
    // 永不评估)→ 后续 allow 永远 false;有兜底则 3 次失败被计入 → 重新 OPEN → 冷却后可再探测。
    for (int i = 0; i < 3; i++) {
      assertThatThrownBy(() -> g.dispatch(command("t1", "API", "ch-1")))
          .isInstanceOf(RuntimeException.class);
    }
    Thread.sleep(60); // 冷却后应能再次探测(未被永久 brick)
    assertThat(realCb.allow("t1|API|ch-1"))
        .as("key must recover after cooldown, not be permanently bricked")
        .isTrue();
  }

  @Test
  void shouldRejectNonOfficialChannelTypeBeforeAdapterLookup() {
    DispatchResult result = gateway.dispatch(command("t1", "WEBHOOK_RAW", "ch-1"));

    assertThat(result.success()).isFalse();
    assertThat(result.message()).isEqualTo("unsupported channel type: WEBHOOK_RAW");
    verify(httpAdapter, never()).supports("WEBHOOK_RAW");
    verify(httpAdapter, never()).dispatch(any());
    verify(deliveryMetrics).recordDelivery("WEBHOOK_RAW", false, false);
  }

  @Test
  void readbackSize_rejectsUnknownChannelType_beforeAdapterLookup() {
    OptionalLong result = gateway.readbackSize(command("t1", "WEBHOOK_RAW", "ch-1"));

    assertThat(result).isEmpty();
    verify(httpAdapter, never()).supports(anyString());
    verify(httpAdapter, never()).dispatch(any());
  }

  @Test
  void readbackSize_rejectsBlankChannelType() {
    OptionalLong result = gateway.readbackSize(command("t1", "   ", "ch-1"));

    assertThat(result).isEmpty();
    verify(httpAdapter, never()).supports(anyString());
    verify(httpAdapter, never()).dispatch(any());
  }

  @Test
  void shouldNormalizeOfficialChannelTypeBeforeAdapterLookup() {
    DispatchResult success = new DispatchResult(true, "req-1", null, true, false, "ok", null);
    when(httpAdapter.dispatch(any())).thenReturn(success);

    DispatchResult result = gateway.dispatch(command("t1", "api", "ch-1"));

    assertThat(result.success()).isTrue();
    verify(deliveryMetrics).recordDelivery("API", true, false);
  }

  @Test
  void shouldRecordHealthOutcomeAfterDispatch() {
    DispatchResult success = new DispatchResult(true, "req-1", null, true, false, "ok", null);
    when(httpAdapter.dispatch(any())).thenReturn(success);

    gateway.dispatch(command("t1", "API", "ch-1"));

    verify(healthService).recordDispatchOutcome(any(), anyBoolean(), anyString(), any());
  }

  // --- helpers ---

  private static DispatchCommand command(String tenantId, String channelType, String channelCode) {
    Map<String, Object> channelConfig =
        Map.of(
            "channel_type", channelType,
            "channel_code", channelCode);
    return new DispatchCommand(tenantId, "trace-1", Map.of(), channelConfig, null);
  }
}
