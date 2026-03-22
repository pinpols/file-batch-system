package com.example.batch.worker.dispatchs.infrastructure.channel;

import com.example.batch.worker.dispatchs.infrastructure.DispatchDeliveryMetrics;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Routes to {@link DispatchChannelAdapter} by {@code channel_type}; applies per-channel circuit breaker and metrics.
 * <ul>
 *   <li>{@code API} / {@code API_PUSH} — {@link HttpDispatchChannelAdapter}</li>
 *   <li>{@code LOCAL} — {@link LocalDispatchChannelAdapter}</li>
 *   <li>{@code NAS} — {@link NasDispatchChannelAdapter}</li>
 *   <li>{@code OSS} — {@link OssDispatchChannelAdapter}</li>
 *   <li>{@code SFTP} — {@link SftpDispatchChannelAdapter}</li>
 *   <li>{@code EMAIL} — {@link SmtpEmailDispatchChannelAdapter}</li>
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
        String channelType = channelConfig == null ? null : String.valueOf(channelConfig.get(DispatchGatewayConstants.CHANNEL_TYPE_KEY));
        String channelCode = channelConfig == null || channelConfig.get("channel_code") == null
                ? DispatchGatewayConstants.DEFAULT_CHANNEL_CODE
                : String.valueOf(channelConfig.get(DispatchGatewayConstants.CHANNEL_CODE_KEY));
        if (!healthService.allowDispatch(channelConfig)) {
            deliveryMetrics.recordDelivery(channelType, false, true);
            return new DispatchResult(false, null, null, false, false, "dispatch blocked by channel health backoff", null);
        }
        String cbKey = command.tenantId() + "|" + channelType + "|" + channelCode;
        if (!circuitBreaker.allow(cbKey)) {
            deliveryMetrics.recordDelivery(channelType, false, true);
            return new DispatchResult(false, null, null, false, false, "dispatch circuit open", null);
        }
        DispatchChannelAdapter adapter = adapters.stream()
                .filter(a -> a.supports(channelType))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("unsupported channel type: " + channelType));
        DispatchResult result = adapter.dispatch(command);
        if (result.success()) {
            circuitBreaker.recordSuccess(cbKey);
        } else {
            circuitBreaker.recordFailure(cbKey);
        }
        healthService.recordDispatchOutcome(channelConfig, result.success(), result.message(), result.evidenceRef());
        deliveryMetrics.recordDelivery(channelType, result.success(), false);
        return result;
    }
}
