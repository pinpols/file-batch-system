package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("渠道分发网关:适配器解析与投递记账、健康与熔断拦截、许可释放与回读守卫")
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

    gateway = new DispatchChannelGateway(
        List.of(httpAdapter), circuitBreaker, deliveryMetrics, healthService);
    clearInvocations(httpAdapter);
  }

  @Test
  @DisplayName("有适配器支持该渠道类型时投递成功,并按正常结果记录投递指标")
  void shouldDispatchSuccessfullyViaMatchingAdapter() {
    DispatchResult success = new DispatchResult(true, "req-1", null, true, false, "ok", null);
    when(httpAdapter.dispatch(any())).thenReturn(success);

    DispatchResult result = gateway.dispatch(command("t1", "API", "ch-1"));

    assertThat(result.success()).isTrue();
    verify(deliveryMetrics).recordDelivery("API", true, false);
  }

  @Test
  @DisplayName("适配器投递失败时结果判失败,并按失败结果记录投递指标")
  void shouldRecordCircuitBreakerFailureOnAdapterFailure() {
    DispatchResult failure = new DispatchResult(false, null, null, false, false, "timeout", null);
    when(httpAdapter.dispatch(any())).thenReturn(failure);

    DispatchResult result = gateway.dispatch(command("t1", "API", "ch-1"));

    assertThat(result.success()).isFalse();
    verify(deliveryMetrics).recordDelivery("API", false, false);
  }

  @Test
  @DisplayName("健康检查拒绝投递时直接失败并给出退避原因,不调用适配器且按熔断拒绝计数")
  void shouldBlockWhenHealthServiceRejectsDispatch() {
    when(healthService.allowDispatch(any())).thenReturn(false);

    DispatchResult result = gateway.dispatch(command("t1", "API", "ch-1"));

    assertThat(result.success()).isFalse();
    assertThat(result.message()).contains("health backoff");
    verify(httpAdapter, never()).dispatch(any());
    verify(deliveryMetrics).recordDelivery("API", false, true);
  }

  @Test
  @DisplayName("渠道已熔断开路时直接失败并给出开路原因,不调用适配器且按熔断拒绝计数")
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

    DispatchChannelGateway gatewayWithOpenCircuit = new DispatchChannelGateway(
        List.of(httpAdapter), shortBreaker, deliveryMetrics, healthService);

    DispatchResult result = gatewayWithOpenCircuit.dispatch(command("t1", "API", "ch-1"));

    assertThat(result.success()).isFalse();
    assertThat(result.message()).contains("circuit open");
    verify(httpAdapter, never()).dispatch(any());
    verify(deliveryMetrics).recordDelivery("API", false, true);
  }

  @Test
  @DisplayName("没有任何适配器支持该渠道类型时抛出非法状态,并说明不支持的渠道类型")
  void shouldThrowWhenNoAdapterSupportsChannelType() {
    assertThatThrownBy(() -> gateway.dispatch(command("t1", "SFTP", "ch-1")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unsupported channel type: SFTP");
  }

  @Test
  @DisplayName("多个适配器同时支持同一渠道类型时快速失败,构造网关即报重复适配器")
  void shouldFailFastWhenMultipleAdaptersSupportSameChannelType() {
    DispatchChannelAdapter first = mock(DispatchChannelAdapter.class);
    DispatchChannelAdapter second = mock(DispatchChannelAdapter.class);
    when(first.supports("SFTP")).thenReturn(true);
    when(second.supports("SFTP")).thenReturn(true);

    assertThatThrownBy(() -> new DispatchChannelGateway(
            List.of(first, second), circuitBreaker, deliveryMetrics, healthService))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("duplicate dispatch channel adapter for channelType=SFTP");
  }

  // --- I-1 许可泄漏兜底:allow() 后逃逸异常必须配对释放熔断许可,否则 HALF_OPEN 永久 brick ---

  @Test
  @DisplayName("适配器投递抛异常时熔断许可被释放,异常继续向上抛出")
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
  @DisplayName("解析适配器阶段抛异常时熔断许可同样被释放,异常继续向上抛出")
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
  @DisplayName("半开探测抛异常耗尽试探预算后,冷却结束仍可再探测,渠道不会被永久卡死")
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
  @DisplayName("非官方渠道类型在查适配器之前就被拒绝,并计入失败投递指标")
  void shouldRejectNonOfficialChannelTypeBeforeAdapterLookup() {
    DispatchResult result = gateway.dispatch(command("t1", "WEBHOOK_RAW", "ch-1"));

    assertThat(result.success()).isFalse();
    assertThat(result.message()).isEqualTo("unsupported channel type: WEBHOOK_RAW");
    verify(httpAdapter, never()).supports("WEBHOOK_RAW");
    verify(httpAdapter, never()).dispatch(any());
    verify(deliveryMetrics).recordDelivery("WEBHOOK_RAW", false, false);
  }

  @Test
  @DisplayName("回读大小遇到未知渠道类型时在查适配器之前返回空,完全不触碰适配器")
  void readbackSize_rejectsUnknownChannelType_beforeAdapterLookup() {
    OptionalLong result = gateway.readbackSize(command("t1", "WEBHOOK_RAW", "ch-1"));

    assertThat(result).isEmpty();
    verify(httpAdapter, never()).supports(anyString());
    verify(httpAdapter, never()).dispatch(any());
  }

  @Test
  @DisplayName("回读大小遇到空白渠道类型时返回空,完全不触碰适配器")
  void readbackSize_rejectsBlankChannelType() {
    OptionalLong result = gateway.readbackSize(command("t1", "   ", "ch-1"));

    assertThat(result).isEmpty();
    verify(httpAdapter, never()).supports(anyString());
    verify(httpAdapter, never()).dispatch(any());
  }

  @Test
  @DisplayName("官方渠道类型大小写不一先归一化再查适配器,投递成功并按规范类型记账")
  void shouldNormalizeOfficialChannelTypeBeforeAdapterLookup() {
    DispatchResult success = new DispatchResult(true, "req-1", null, true, false, "ok", null);
    when(httpAdapter.dispatch(any())).thenReturn(success);

    DispatchResult result = gateway.dispatch(command("t1", "api", "ch-1"));

    assertThat(result.success()).isTrue();
    verify(deliveryMetrics).recordDelivery("API", true, false);
  }

  @Test
  @DisplayName("投递结束后把本次结果回报给渠道健康服务")
  void shouldRecordHealthOutcomeAfterDispatch() {
    DispatchResult success = new DispatchResult(true, "req-1", null, true, false, "ok", null);
    when(httpAdapter.dispatch(any())).thenReturn(success);

    gateway.dispatch(command("t1", "API", "ch-1"));

    verify(healthService).recordDispatchOutcome(any(), anyBoolean(), anyString(), any());
  }

  // --- helpers ---

  private static DispatchCommand command(String tenantId, String channelType, String channelCode) {
    Map<String, Object> channelConfig = Map.of(
        "channel_type", channelType,
        "channel_code", channelCode);
    return new DispatchCommand(tenantId, "trace-1", Map.of(), channelConfig, null);
  }
}
