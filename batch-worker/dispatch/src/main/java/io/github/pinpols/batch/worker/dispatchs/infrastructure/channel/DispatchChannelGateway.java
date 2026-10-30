package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import io.github.pinpols.batch.common.logging.LogSanitizer;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.DispatchDeliveryMetrics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public class DispatchChannelGateway {

  private final Map<String, DispatchChannelAdapter> adaptersByType;
  private final DispatchChannelCircuitBreaker circuitBreaker;
  private final DispatchDeliveryMetrics deliveryMetrics;
  private final DispatchChannelHealthService healthService;

  public DispatchChannelGateway(
      List<DispatchChannelAdapter> adapters,
      DispatchChannelCircuitBreaker circuitBreaker,
      DispatchDeliveryMetrics deliveryMetrics,
      DispatchChannelHealthService healthService) {
    this.adaptersByType = buildAdapterRegistry(adapters);
    this.circuitBreaker = circuitBreaker;
    this.deliveryMetrics = deliveryMetrics;
    this.healthService = healthService;
  }

  public DispatchResult dispatch(DispatchCommand command) {
    Map<String, Object> channelConfig = command.channelConfig();
    Object rawChannelType =
        channelConfig == null ? null : channelConfig.get(DispatchGatewayConstants.CHANNEL_TYPE_KEY);
    // P0: 旧实现走 String.valueOf(null) 得到字面量 "null",再 supports("null") 一路 false 抛
    // IllegalStateException,被外层包成 INFRA_ERROR,掩盖"配置漏字段"这一业务原因。改为提前
    // 判 null 返回业务失败,根因可读。
    boolean channelTypeIsMissingOrBlank =
        EmptyChecks.isNull(rawChannelType) || !Texts.hasText(String.valueOf(rawChannelType));
    if (channelTypeIsMissingOrBlank) {
      deliveryMetrics.recordDelivery(null, false, false);
      return DispatchResult.failureWithoutReceipt("channel_type missing in channel_config");
    }
    String requestedChannelType = String.valueOf(rawChannelType);
    Optional<String> normalizedChannelType =
        DispatchChannelTypePolicy.normalize(requestedChannelType);
    String channelType = normalizedChannelType.orElse(null);
    if (channelType == null) {
      deliveryMetrics.recordDelivery(requestedChannelType, false, false);
      return DispatchResult.failureWithoutReceipt(
          "unsupported channel type: " + requestedChannelType);
    }
    // channelConfig 非空由上方 rawChannelType 判空早返保证
    String channelCode = channelConfig.get("channel_code") == null
        ? DispatchGatewayConstants.DEFAULT_CHANNEL_CODE
        : String.valueOf(channelConfig.get(DispatchGatewayConstants.CHANNEL_CODE_KEY));
    if (!healthService.allowDispatch(channelConfig)) {
      deliveryMetrics.recordDelivery(channelType, false, true);
      return DispatchResult.failureWithoutReceipt("dispatch blocked by channel health backoff");
    }
    String cbKey = command.tenantId() + "|" + channelType + "|" + channelCode;
    if (!circuitBreaker.allow(cbKey)) {
      deliveryMetrics.recordDelivery(channelType, false, true);
      return DispatchResult.failureWithoutReceipt("dispatch circuit open");
    }
    // allow() 已取一个熔断许可(HALF_OPEN 探测许可有限)。适配器解析/投递异常必须释放许可；
    // 外部投递返回后，健康快照是辅助状态，不能把已成功的外部副作用变成失败并导致重投。
    DispatchResult result;
    try {
      DispatchChannelAdapter adapter = resolveAdapter(channelType);
      result = adapter.dispatch(command);
    } catch (RuntimeException e) {
      circuitBreaker.recordFailure(cbKey);
      throw e;
    }
    if (result.success()) {
      circuitBreaker.recordSuccess(cbKey);
    } else {
      circuitBreaker.recordFailure(cbKey);
    }
    try {
      healthService.recordDispatchOutcome(
          channelConfig, result.success(), result.message(), result.evidenceRef());
    } catch (RuntimeException e) {
      log.warn(
          "Dispatch health state could not be persisted after channel outcome: tenantId={}, channelCode={}, cause={}",
          LogSanitizer.value(command.tenantId()),
          LogSanitizer.value(channelCode),
          SwallowedExceptionLogger.summary(e));
    }
    deliveryMetrics.recordDelivery(channelType, result.success(), false);
    return result;
  }

  /**
   * ADR-041 Phase1.5:解析渠道适配器,若其实现 {@link DispatchReadbackCapable} 则回读目的端字节数;否则(不支持回读) 返回
   * empty。仅在投递成功后调用(adapter 必能解析),故不会触发 unsupported 异常。
   */
  public OptionalLong readbackSize(DispatchCommand command) {
    Map<String, Object> channelConfig = command.channelConfig();
    Object rawChannelType =
        channelConfig == null ? null : channelConfig.get(DispatchGatewayConstants.CHANNEL_TYPE_KEY);
    boolean channelTypeIsMissingOrBlank =
        EmptyChecks.isNull(rawChannelType) || !Texts.hasText(String.valueOf(rawChannelType));
    if (channelTypeIsMissingOrBlank) {
      return OptionalLong.empty();
    }
    Optional<String> channelType =
        DispatchChannelTypePolicy.normalize(String.valueOf(rawChannelType));
    if (channelType.isEmpty()) {
      return OptionalLong.empty();
    }
    DispatchChannelAdapter adapter = resolveAdapter(channelType.get());
    if (adapter instanceof DispatchReadbackCapable capable) {
      return capable.readbackSize(command);
    }
    return OptionalLong.empty();
  }

  public boolean supports(String requestedChannelType) {
    return DispatchChannelTypePolicy.normalize(requestedChannelType)
        .map(adaptersByType::containsKey)
        .orElse(false);
  }

  private DispatchChannelAdapter resolveAdapter(String channelType) {
    DispatchChannelAdapter adapter = adaptersByType.get(channelType);
    if (EmptyChecks.isNull(adapter)) {
      throw new IllegalStateException("unsupported channel type: " + channelType);
    }
    return adapter;
  }

  // S2259 无法识别 EmptyChecks.isNotNull(matched) 对后续解引用的保护。
  @SuppressWarnings("java:S2259")
  private static Map<String, DispatchChannelAdapter> buildAdapterRegistry(
      List<DispatchChannelAdapter> adapters) {
    Map<String, DispatchChannelAdapter> registry = new LinkedHashMap<>();
    for (String channelType : DispatchChannelTypePolicy.allowedTypes()) {
      DispatchChannelAdapter matched = null;
      for (DispatchChannelAdapter adapter : adapters) {
        if (!adapter.supports(channelType)) {
          continue;
        }
        if (EmptyChecks.isNotNull(matched) && matched != adapter) {
          throw new IllegalStateException("duplicate dispatch channel adapter for channelType="
              + channelType
              + ": "
              + matched.getClass().getName()
              + ", "
              + adapter.getClass().getName());
        }
        matched = adapter;
      }
      if (EmptyChecks.isNotNull(matched)) {
        registry.put(channelType, matched);
      }
    }
    return Map.copyOf(registry);
  }
}
