package com.example.batch.worker.dispatchs.infrastructure.channel;

import com.example.batch.worker.dispatchs.config.DispatchChannelHealthProperties;
import com.example.batch.worker.dispatchs.config.DispatchCircuitBreakerProperties;
import com.example.batch.common.config.MinioStorageProperties;
import com.example.batch.worker.dispatchs.infrastructure.ChannelConfigMerge;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.minio.MinioClient;
import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 分发渠道健康管理服务，负责探针执行、状态更新及分发前健康门控。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DispatchChannelHealthService {

    private static final int MAX_PROBE_CHANNEL_BATCH = 1000;

    private final DispatchChannelHealthRepository repository;
    private final DispatchChannelHealthProperties properties;
    private final DispatchCircuitBreakerProperties circuitBreakerProperties;
    private final MinioStorageProperties minioStorageProperties;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private MinioClient minioClient;
    private final AtomicLong probeSuccessCount = new AtomicLong();
    private final AtomicLong probeFailureCount = new AtomicLong();

    @PostConstruct
    void init() {
        if (minioStorageProperties != null
                && StringUtils.hasText(minioStorageProperties.getEndpoint())
                && StringUtils.hasText(minioStorageProperties.getAccessKey())
                && StringUtils.hasText(minioStorageProperties.getSecretKey())) {
            this.minioClient = MinioClient.builder()
                    .endpoint(minioStorageProperties.getEndpoint())
                    .credentials(minioStorageProperties.getAccessKey(), minioStorageProperties.getSecretKey())
                    .build();
        }
        meterRegistry.gauge("batch.dispatch.channel.probe.successes", probeSuccessCount);
        meterRegistry.gauge("batch.dispatch.channel.probe.failures", probeFailureCount);
    }

    public boolean allowDispatch(Map<String, Object> channelConfig) {
        if (!properties.isEnabled() || channelConfig == null || channelConfig.isEmpty()) {
            return true;
        }
        DispatchChannelHealthSnapshot snapshot = repository.findHealth(stringValue(channelConfig.get("tenant_id")), stringValue(channelConfig.get("channel_code")));
        if (snapshot == null) {
            return true;
        }
        if ("HEALTHY".equalsIgnoreCase(snapshot.healthStatus())) {
            return true;
        }
        if (snapshot.nextProbeAt() == null) {
            return false;
        }
        return !java.time.Instant.now().isBefore(snapshot.nextProbeAt());
    }

    public void recordDispatchOutcome(Map<String, Object> channelConfig, boolean success, String message, String evidence) {
        if (!properties.isEnabled() || channelConfig == null || channelConfig.isEmpty()) {
            return;
        }
        String tenantId = stringValue(channelConfig.get("tenant_id"));
        String channelCode = stringValue(channelConfig.get("channel_code"));
        String channelType = stringValue(channelConfig.get("channel_type"));
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(channelCode)) {
            return;
        }
        DispatchChannelHealthSnapshot snapshot = repository.findHealth(tenantId, channelCode);
        if (success) {
            repository.upsertHealth(new DispatchChannelHealthSnapshot(
                    tenantId, channelCode, channelType, "HEALTHY", 0,
                    java.time.Instant.now(), java.time.Instant.now(),
                    snapshot == null ? null : snapshot.lastFailureAt(),
                    java.time.Instant.now().plusMillis(properties.getProbeIntervalMillis()),
                    message, evidence
            ));
            return;
        }
        int failures = snapshot == null ? 1 : snapshot.consecutiveFailures() + 1;
        long backoff = computeBackoffMillis(failures);
        String status = failures >= Math.max(1, circuitBreakerProperties.getFailureThreshold()) ? "UNHEALTHY" : "DEGRADED";
        repository.upsertHealth(new DispatchChannelHealthSnapshot(
                tenantId, channelCode, channelType, status, failures,
                java.time.Instant.now(), snapshot == null ? null : snapshot.lastSuccessAt(),
                java.time.Instant.now(), java.time.Instant.now().plusMillis(backoff),
                message, evidence
        ));
    }

    public void probeConfiguredChannels() {
        if (!properties.isEnabled()) {
            return;
        }
        List<Map<String, Object>> rows = repository.findEnabledProbeChannels(properties.getProbeChannelTypes(), MAX_PROBE_CHANNEL_BATCH);
        for (Map<String, Object> row : rows) {
            try {
                probeOne(row);
            } catch (Exception exception) {
                probeFailureCount.incrementAndGet();
                log.warn("dispatch channel probe exception: error={}, row={}", exception.getMessage(), row, exception);
            }
        }
    }

    public DispatchChannelProbeResult probeOne(Map<String, Object> rawRow) {
        Map<String, Object> channelConfig = mergedConfig(rawRow);
        if (channelConfig.isEmpty()) {
            return new DispatchChannelProbeResult(false, "channel config missing", null);
        }
        String tenantId = stringValue(channelConfig.get("tenant_id"));
        String channelCode = stringValue(channelConfig.get("channel_code"));
        String channelType = stringValue(channelConfig.get("channel_type"));
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(channelCode) || !StringUtils.hasText(channelType)) {
            return new DispatchChannelProbeResult(false, "probe target missing tenant/channel/type", null);
        }
        DispatchChannelHealthSnapshot snapshot = repository.findHealth(tenantId, channelCode);
        if (snapshot != null && snapshot.nextProbeAt() != null && java.time.Instant.now().isBefore(snapshot.nextProbeAt())) {
            return new DispatchChannelProbeResult(false, "probe deferred until backoff expires", null);
        }
        DispatchChannelProbeResult result = RemoteFilesystemDispatchSupport.probeChannel(channelConfig, minioStorageProperties, minioClient);
        recordProbeResult(channelConfig, result);
        if (result.success()) {
            probeSuccessCount.incrementAndGet();
        } else {
            probeFailureCount.incrementAndGet();
            logProbeFailure(tenantId, channelCode, channelType, result.message(), result.evidenceRef());
        }
        return result;
    }

    private void recordProbeResult(Map<String, Object> channelConfig, DispatchChannelProbeResult result) {
        recordDispatchOutcome(channelConfig, result.success(), result.message(), result.evidenceRef());
    }

    private Map<String, Object> mergedConfig(Map<String, Object> rawRow) {
        try {
            return ChannelConfigMerge.merge(rawRow, objectMapper);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private long computeBackoffMillis(int failures) {
        long base = Math.max(1L, properties.getProbeIntervalMillis());
        long backoff = base;
        for (int i = 1; i < failures; i++) {
            if (backoff >= properties.getMaxBackoffMillis()) {
                return properties.getMaxBackoffMillis();
            }
            backoff = Math.min(properties.getMaxBackoffMillis(), backoff * 2L);
        }
        return backoff;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return StringUtils.hasText(text) && !"null".equalsIgnoreCase(text) ? text.trim() : null;
    }

    /**
     * 只读挂载、权限不足等本机联调场景频发，降级为 DEBUG 避免噪音；其余情况仍使用 WARN 以便发现真实故障。
     */
    private void logProbeFailure(String tenantId, String channelCode, String channelType, String message, String evidence) {
        String m = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (m.contains("read-only") || m.contains("not writable") || m.contains("permission denied")) {
            log.debug(
                    "dispatch channel probe failed (environment): tenantId={}, channelCode={}, channelType={}, message={}, evidence={}",
                    tenantId, channelCode, channelType, message, evidence);
            return;
        }
        log.warn("dispatch channel probe failed: tenantId={}, channelCode={}, channelType={}, message={}, evidence={}",
                tenantId, channelCode, channelType, message, evidence);
    }
}
