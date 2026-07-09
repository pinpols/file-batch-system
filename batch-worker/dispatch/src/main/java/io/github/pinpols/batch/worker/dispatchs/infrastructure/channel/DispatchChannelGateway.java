package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.DispatchDeliveryMetrics;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
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

  // 以 channelType(按传入值大小写敏感)为 key 的 adapter 查找缓存 —— 避免每次 dispatch 都做
  // O(n) 的 adapters.stream().filter().findFirst()。首次未命中时懒加载填充,
  // 从而保留动态 supports() 契约(单测里 adapter 会 mock 不同类型)。
  private final ConcurrentMap<String, DispatchChannelAdapter> adapterCache =
      new ConcurrentHashMap<>();

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
    String requestedChannelType = String.valueOf(rawChannelType);
    String channelType = DispatchChannelTypePolicy.normalize(requestedChannelType).orElse(null);
    if (channelType == null) {
      deliveryMetrics.recordDelivery(requestedChannelType, false, false);
      return new DispatchResult(
          false,
          null,
          null,
          false,
          false,
          "unsupported channel type: " + requestedChannelType,
          null);
    }
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
    // allow() 已取一个熔断许可(HALF_OPEN 探测许可有限)。从此处到 record* 之间任何逃逸异常
    // (resolveAdapter 未知渠道 / adapter.dispatch 抛错)都必须把许可配对释放,否则该 cbKey 会永久卡
    // HALF_OPEN、永久拒投递直到重启(静默 brick)。以 recordFailure(=R4J onError,释放许可并计一次失败)兜底。
    try {
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
    } catch (RuntimeException e) {
      circuitBreaker.recordFailure(cbKey);
      throw e;
    }
  }

  /**
   * ADR-041 Phase1.5:解析渠道适配器,若其实现 {@link DispatchReadbackCapable} 则回读目的端字节数;否则(不支持回读) 返回
   * empty。仅在投递成功后调用(adapter 必能解析),故不会触发 unsupported 异常。
   */
  public OptionalLong readbackSize(DispatchCommand command) {
    Map<String, Object> channelConfig = command.channelConfig();
    Object rawChannelType =
        channelConfig == null ? null : channelConfig.get(DispatchGatewayConstants.CHANNEL_TYPE_KEY);
    if (rawChannelType == null || !Texts.hasText(String.valueOf(rawChannelType))) {
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
