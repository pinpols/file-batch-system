package com.example.batch.worker.dispatchs.infrastructure.channel;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.config.MinioStorageProperties;
import com.example.batch.common.utils.SecretMasking;
import com.example.batch.common.utils.Texts;
import com.example.batch.worker.dispatchs.config.DispatchChannelHealthProperties;
import com.example.batch.worker.dispatchs.config.DispatchCircuitBreakerProperties;
import com.example.batch.worker.dispatchs.infrastructure.ChannelConfigMerge;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.minio.MinioClient;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 分发渠道健康管理服务，负责探针执行、状态更新及分发前健康门控。
 *
 * <p>健康状态机：HEALTHY → DEGRADED（连续失败未达阈值）→ UNHEALTHY（达阈值）。 UNHEALTHY 渠道不直接拦截分发，而是等 {@code
 * nextProbeAt} 过期后放行一次， 让真实分发结果作为隐式探针（half-open 模式），避免独立探针与实际分发结论不一致。
 * 探针调度器定期对启用了探针的渠道类型（SFTP/OSS/NAS 等）主动拨测， 拨测结果与正常分发结果复用同一条 {@code recordDispatchOutcome} 路径写入快照表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DispatchChannelHealthService {

  // 单次探针查询上限，防止渠道数量过大时探针调度器阻塞 DB 连接池
  private static final int MAX_PROBE_CHANNEL_BATCH = 1000;

  private final DispatchChannelHealthRepository repository;
  private final DispatchChannelHealthProperties properties;
  private final DispatchCircuitBreakerProperties circuitBreakerProperties;
  private final MinioStorageProperties minioStorageProperties;
  private final BatchSecurityProperties securityProperties;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;
  private MinioClient minioClient;
  private final AtomicLong probeSuccessCount = new AtomicLong();
  private final AtomicLong probeFailureCount = new AtomicLong();

  @PostConstruct
  void init() {
    if (minioStorageProperties != null
        && Texts.hasText(minioStorageProperties.getEndpoint())
        && Texts.hasText(minioStorageProperties.getAccessKey())
        && Texts.hasText(minioStorageProperties.getSecretKey())) {
      this.minioClient =
          MinioClient.builder()
              .endpoint(minioStorageProperties.getEndpoint())
              .credentials(
                  minioStorageProperties.getAccessKey(), minioStorageProperties.getSecretKey())
              .build();
    }
    meterRegistry.gauge("batch.dispatch.channel.probe.successes", probeSuccessCount);
    meterRegistry.gauge("batch.dispatch.channel.probe.failures", probeFailureCount);
  }

  public boolean allowDispatch(Map<String, Object> channelConfig) {
    if (!properties.isEnabled() || channelConfig == null || channelConfig.isEmpty()) {
      return true;
    }
    ChannelIdentity channel = ChannelIdentity.from(channelConfig);
    // T-3：没有 tenantId/channelCode 无法查健康快照；此时放行（健康门控不适用），
    // 避免 repository.findHealth(null, null) 走 SQL 返回脏数据或 DAO 层 NPE
    if (!channel.isTargetable()) {
      return true;
    }
    DispatchChannelHealthSnapshot snapshot =
        repository.findHealth(channel.tenantId(), channel.channelCode());
    if (snapshot == null) {
      return true;
    }
    if ("HEALTHY".equalsIgnoreCase(snapshot.healthStatus())) {
      return true;
    }
    if (snapshot.nextProbeAt() == null) {
      return false;
    }
    Instant now = Instant.now();
    if (now.isBefore(snapshot.nextProbeAt())) {
      return false; // 仍在 backoff 窗口
    }
    // A-3.9：半开阶段只让一个线程拿到探针机会——CAS 把 next_probe_at 推到未来，
    // 失败即说明另一线程已抢到通行证。holdMillis 覆盖典型 dispatch 最长耗时，
    // 防止本线程还没 recordDispatchOutcome 就又有新线程通过。
    Instant hold = now.plusMillis(Math.max(1_000L, properties.getHalfOpenHoldMillis()));
    boolean claimed =
        repository.tryClaimHalfOpenProbe(channel.tenantId(), channel.channelCode(), now, hold);
    if (!claimed && log.isDebugEnabled()) {
      log.debug(
          "half-open probe slot already taken by another worker: tenantId={}, channelCode={}",
          channel.tenantId(),
          channel.channelCode());
    }
    return claimed;
  }

  public void recordDispatchOutcome(
      Map<String, Object> channelConfig, boolean success, String message, String evidence) {
    if (!properties.isEnabled() || channelConfig == null || channelConfig.isEmpty()) {
      return;
    }
    ChannelIdentity channel = ChannelIdentity.from(channelConfig);
    if (!channel.isTargetable()) {
      return;
    }
    Instant now = Instant.now();
    if (success) {
      // P2：成功走原子 upsert——HEALTHY + failures=0 + next_probe=now+probeInterval
      DispatchHealthUpsertCommand successCmd =
          DispatchHealthUpsertCommand.builder()
              .tenantId(channel.tenantId())
              .channelCode(channel.channelCode())
              .channelType(channel.channelType())
              .now(now)
              .nextProbeAt(now.plusMillis(properties.getProbeIntervalMillis()))
              .probeMessage(message)
              .probeEvidence(evidence)
              .build();
      repository.upsertSuccess(successCmd);
      return;
    }
    // P2：失败两步走。
    // 第 1 步 upsert：先占一个条目 + failures 由 COALESCE(count,0)+1 在 SQL 侧递增，
    // 首次失败（INSERT 路径）用 base probeInterval 做 placeholder。
    // 第 2 步 recalcBackoff：按新的 failures 重算真实 next_probe_at（指数退避），
    // 该 UPDATE 作用于同一行且只读自己的 consecutive_failures 字段，无竞争。
    Instant firstFailureBackoffAt = now.plusMillis(properties.getProbeIntervalMillis());
    DispatchHealthUpsertCommand failureCmd =
        DispatchHealthUpsertCommand.builder()
            .tenantId(channel.tenantId())
            .channelCode(channel.channelCode())
            .channelType(channel.channelType())
            .now(now)
            .nextProbeAt(firstFailureBackoffAt)
            .failureThreshold(Math.max(1, circuitBreakerProperties.getFailureThreshold()))
            .probeMessage(message)
            .probeEvidence(evidence)
            .build();
    repository.upsertFailureAndBump(failureCmd);
    DispatchHealthUpsertCommand recalcCmd =
        DispatchHealthUpsertCommand.builder()
            .tenantId(channel.tenantId())
            .channelCode(channel.channelCode())
            .channelType(channel.channelType())
            .now(now)
            .probeIntervalMillis(properties.getProbeIntervalMillis())
            .maxBackoffMillis(properties.getMaxBackoffMillis())
            .build();
    repository.recalcBackoff(recalcCmd);
  }

  public void probeConfiguredChannels() {
    if (!properties.isEnabled()) {
      return;
    }
    List<Map<String, Object>> rows =
        repository.findEnabledProbeChannels(
            properties.getProbeChannelTypes(), MAX_PROBE_CHANNEL_BATCH);
    for (Map<String, Object> row : rows) {
      try {
        probeOne(row);
      } catch (Exception exception) {
        probeFailureCount.incrementAndGet();
        // R-4.10：row 可能含 password / api_key 等凭证，脱敏后再打日志
        log.warn(
            "dispatch channel probe exception: error={}, row={}",
            exception.getMessage(),
            SecretMasking.maskSensitiveKeys(row),
            exception);
      }
    }
  }

  public DispatchChannelProbeResult probeOne(Map<String, Object> rawRow) {
    Map<String, Object> channelConfig = mergedConfig(rawRow);
    if (channelConfig.isEmpty()) {
      return new DispatchChannelProbeResult(false, "channel config missing", null);
    }
    ChannelIdentity channel = ChannelIdentity.from(channelConfig);
    if (!channel.isFullyIdentified()) {
      return new DispatchChannelProbeResult(
          false, "probe target missing tenant/channel/type", null);
    }
    DispatchChannelHealthSnapshot snapshot =
        repository.findHealth(channel.tenantId(), channel.channelCode());
    if (snapshot != null
        && snapshot.nextProbeAt() != null
        && Instant.now().isBefore(snapshot.nextProbeAt())) {
      return new DispatchChannelProbeResult(false, "probe deferred until backoff expires", null);
    }
    DispatchChannelProbeResult result =
        RemoteFilesystemDispatchSupport.probeChannel(
            channelConfig, minioStorageProperties, minioClient, !securityProperties.isBypassMode());
    recordProbeResult(channelConfig, result);
    if (result.success()) {
      probeSuccessCount.incrementAndGet();
    } else {
      probeFailureCount.incrementAndGet();
      logProbeFailure(
          channel.tenantId(),
          channel.channelCode(),
          channel.channelType(),
          result.message(),
          result.evidenceRef());
    }
    return result;
  }

  private void recordProbeResult(
      Map<String, Object> channelConfig, DispatchChannelProbeResult result) {
    recordDispatchOutcome(channelConfig, result.success(), result.message(), result.evidenceRef());
  }

  private Map<String, Object> mergedConfig(Map<String, Object> rawRow) {
    try {
      return ChannelConfigMerge.merge(rawRow, objectMapper);
    } catch (Exception ex) {
      return Map.of();
    }
  }

  // 指数退避：每次失败将等待时间翻倍，上限由 maxBackoffMillis 截断，防止长期故障渠道被频繁拨测
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

  private static String stringValue(Object value) {
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value);
    return Texts.hasText(text) && !"null".equalsIgnoreCase(text) ? text.trim() : null;
  }

  /**
   * 渠道健康路径上的身份三元组（tenantId / channelCode / channelType）。把原本在 3 个方法里重复 3 次的 {@code
   * channelConfig.get("tenant_id")} 串键提取收敛到一处，同时明确"什么叫能做健康门控" （{@link
   * #isTargetable()}）与"什么叫探针目标完整"（{@link #isFullyIdentified()}）两种校验语义。
   */
  private record ChannelIdentity(String tenantId, String channelCode, String channelType) {
    static ChannelIdentity from(Map<String, Object> channelConfig) {
      return new ChannelIdentity(
          stringValue(channelConfig.get("tenant_id")),
          stringValue(channelConfig.get("channel_code")),
          stringValue(channelConfig.get("channel_type")));
    }

    boolean isTargetable() {
      return Texts.hasText(tenantId) && Texts.hasText(channelCode);
    }

    boolean isFullyIdentified() {
      return isTargetable() && Texts.hasText(channelType);
    }
  }

  /** 只读挂载、权限不足等本机联调场景频发，降级为 DEBUG 避免噪音；其余情况仍使用 WARN 以便发现真实故障。 */
  private void logProbeFailure(
      String tenantId, String channelCode, String channelType, String message, String evidence) {
    String m = message == null ? "" : message.toLowerCase(Locale.ROOT);
    if (m.contains("read-only") || m.contains("not writable") || m.contains("permission denied")) {
      log.debug(
          "dispatch channel probe failed (environment): tenantId={}, channelCode={},"
              + " channelType={}, message={}, evidence={}",
          tenantId,
          channelCode,
          channelType,
          message,
          evidence);
      return;
    }
    log.warn(
        "dispatch channel probe failed: tenantId={}, channelCode={}, channelType={}, message={},"
            + " evidence={}",
        tenantId,
        channelCode,
        channelType,
        message,
        evidence);
  }
}
