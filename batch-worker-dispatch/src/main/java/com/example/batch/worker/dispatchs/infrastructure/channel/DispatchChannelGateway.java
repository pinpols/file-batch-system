package com.example.batch.worker.dispatchs.infrastructure.channel;

import com.example.batch.worker.dispatchs.infrastructure.DispatchDeliveryMetrics;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 根据 {@code channel_type} 将分发请求路由到对应的 {@link DispatchChannelAdapter}， 并统一应用渠道级熔断器和指标采集。
 *
 * <ul>
 *   <li>{@code API} / {@code API_PUSH} — {@link HttpDispatchChannelAdapter}
 *   <li>{@code LOCAL} — {@link LocalDispatchChannelAdapter}
 *   <li>{@code NAS} — {@link NasDispatchChannelAdapter}
 *   <li>{@code OSS} — {@link OssDispatchChannelAdapter}
 *   <li>{@code SFTP} — {@link SftpDispatchChannelAdapter}
 *   <li>{@code EMAIL} — {@link SmtpEmailDispatchChannelAdapter}
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class DispatchChannelGateway {

  private final List<DispatchChannelAdapter> adapters;
  private final DispatchChannelCircuitBreaker circuitBreaker;
  private final DispatchDeliveryMetrics deliveryMetrics;
  private final DispatchChannelHealthService healthService;

  public DispatchResult dispatch(DispatchCommand command) {
    Map<String, Object> channelConfig = command.channelConfig();
    String channelType =
        channelConfig == null
            ? null
            : String.valueOf(channelConfig.get(DispatchGatewayConstants.CHANNEL_TYPE_KEY));
    String channelCode =
        channelConfig == null || channelConfig.get("channel_code") == null
            ? DispatchGatewayConstants.DEFAULT_CHANNEL_CODE
            : String.valueOf(channelConfig.get(DispatchGatewayConstants.CHANNEL_CODE_KEY));
    if (!healthService.allowDispatch(channelConfig)) {
      deliveryMetrics.recordDelivery(channelType, false, true);
      return new DispatchResult(
          false, null, null, false, false, "dispatch blocked by channel health backoff", null);
    }
    String cbKey = command.tenantId() + "|" + channelType + "|" + channelCode;
    if (!circuitBreaker.allow(cbKey)) {
      deliveryMetrics.recordDelivery(channelType, false, true);
      return new DispatchResult(false, null, null, false, false, "dispatch circuit open", null);
    }
    DispatchChannelAdapter adapter =
        adapters.stream()
            .filter(a -> a.supports(channelType))
            .findFirst()
            .orElseThrow(
                () -> new IllegalStateException("unsupported channel type: " + channelType));
    DispatchResult result = adapter.dispatch(command);
    if (result.success()) {
      circuitBreaker.recordSuccess(cbKey);
    } else {
      circuitBreaker.recordFailure(cbKey);
    }
    healthService.recordDispatchOutcome(
        channelConfig, result.success(), result.message(), result.evidenceRef());
    deliveryMetrics.recordDelivery(channelType, result.success(), false);
    return result;
  }
}
