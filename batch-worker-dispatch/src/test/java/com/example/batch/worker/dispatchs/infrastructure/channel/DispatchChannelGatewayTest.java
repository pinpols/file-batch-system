package com.example.batch.worker.dispatchs.infrastructure.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.worker.dispatchs.config.DispatchCircuitBreakerProperties;
import com.example.batch.worker.dispatchs.infrastructure.DispatchDeliveryMetrics;
import java.util.List;
import java.util.Map;
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
