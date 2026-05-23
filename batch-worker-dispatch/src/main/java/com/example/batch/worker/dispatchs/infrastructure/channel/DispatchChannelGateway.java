package com.example.batch.worker.dispatchs.infrastructure.channel;

import com.example.batch.common.utils.Texts;
import com.example.batch.worker.dispatchs.infrastructure.DispatchDeliveryMetrics;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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

  // Adapter lookup cache keyed by channelType (case-sensitive as supplied) — avoids the prior
  // O(n) adapters.stream().filter().findFirst() on every dispatch. Populated lazily on first miss
  // so the dynamic supports() contract (adapters mock different types in unit tests) is preserved.
  private final ConcurrentMap<String, DispatchChannelAdapter> adapterCache = new ConcurrentHashMap<>();

  public DispatchResult dispatch(DispatchCommand command) {
    Map<String, Object> channelConfig = command.channelConfig();
    Object rawChannelType =
        channelConfig == null ? null : channelConfig.get(DispatchGatewayConstants.CHANNEL_TYPE_KEY);
    // P0: 旧实现走 String.valueOf(null) 得到字面量 "null",再 supports("null") 一路 false 抛
    // IllegalStateException,被外层包成 INFRA_ERROR,掩盖"配置漏字段"这一业务原因。改为提前
    // 判 null 返回业务失败,根因可读。
    if (rawChannelType == null || !Texts.hasText(String.valueOf(rawChannelType))) {
      deliveryMetrics.recordDelivery(null, false, false);
      return new DispatchResult(
          false, null, null, false, false, "channel_type missing in channel_config", null);
    }
    String channelType = String.valueOf(rawChannelType);
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
    DispatchChannelAdapter adapter = resolveAdapter(channelType);
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

  private DispatchChannelAdapter resolveAdapter(String channelType) {
    DispatchChannelAdapter cached = adapterCache.get(channelType);
    if (cached != null) {
      return cached;
    }
    for (DispatchChannelAdapter adapter : adapters) {
      if (adapter.supports(channelType)) {
        adapterCache.putIfAbsent(channelType, adapter);
        return adapter;
      }
    }
    throw new IllegalStateException("unsupported channel type: " + channelType);
  }
}
