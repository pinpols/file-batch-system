package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.worker.dispatchs.mapper.DispatchChannelConfigView;
import io.github.pinpols.batch.worker.dispatchs.mapper.DispatchChannelHealthMapper;
import io.github.pinpols.batch.worker.dispatchs.mapper.DispatchChannelHealthView;
import io.github.pinpols.batch.worker.dispatchs.param.DispatchChannelHealthBackoffParam;
import io.github.pinpols.batch.worker.dispatchs.param.DispatchChannelHealthFailureParam;
import io.github.pinpols.batch.worker.dispatchs.param.DispatchChannelHealthProbeClaimParam;
import io.github.pinpols.batch.worker.dispatchs.param.DispatchChannelHealthSuccessParam;
import io.github.pinpols.batch.worker.dispatchs.param.DispatchChannelHealthUpsertParam;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** 分发渠道健康状态持久化仓库。 */
@Repository
@RequiredArgsConstructor
public class DispatchChannelHealthRepository {

  private final DispatchChannelHealthMapper mapper;

  public List<DispatchChannelConfigView> findEnabledProbeChannels(List<String> types, int limit) {
    if (types == null || types.isEmpty()) {
      return List.of();
    }
    return mapper.findEnabledProbeChannels(types, limit);
  }

  public DispatchChannelHealthSnapshot findHealth(String tenantId, String channelCode) {
    DispatchChannelHealthView row = mapper.findHealth(tenantId, channelCode);
    return row == null ? null : toSnapshot(row);
  }

  /**
   * A-3.9：CAS 抢占半开探针机会。委托给 Mapper 的条件 UPDATE，仅在 (next_probe_at &lt;= now &amp;&amp; health_status
   * &lt;&gt; 'HEALTHY') 时把 next_probe_at 推到 {@code newNextProbeAt}。
   *
   * @return true = 本线程获得半开通行证；false = 其他线程已抢或已恢复 HEALTHY
   */
  public boolean tryClaimHalfOpenProbe(
      String tenantId, String channelCode, Instant now, Instant newNextProbeAt) {
    Timestamp probeTime = toTimestamp(now);
    Timestamp nextProbeTime = toTimestamp(newNextProbeAt);
    DispatchChannelHealthProbeClaimParam params =
        new DispatchChannelHealthProbeClaimParam(tenantId, channelCode, probeTime, nextProbeTime);
    return mapper.tryClaimHalfOpenProbe(params) > 0;
  }

  /**
   * P2：原子写入"成功"结果，等价于旧 upsertHealth(HEALTHY, failures=0, ...) 但 SQL 侧一次 完成，无 find-then-upsert 竞态。
   */
  public void upsertSuccess(DispatchHealthUpsertCommand cmd) {
    Timestamp probeTime = toTimestamp(cmd.now());
    Timestamp nextProbeTime = toTimestamp(cmd.nextProbeAt());
    DispatchChannelHealthSuccessParam params = DispatchChannelHealthSuccessParam.builder()
        .tenantId(cmd.tenantId())
        .channelCode(cmd.channelCode())
        .channelType(cmd.channelType())
        .now(probeTime)
        .nextProbeAt(nextProbeTime)
        .probeMessage(cmd.probeMessage())
        .probeEvidence(cmd.probeEvidence())
        .build();
    mapper.upsertSuccess(params);
  }

  /**
   * P2：原子写入"失败"结果。consecutive_failures 由 SQL {@code COALESCE(...) + 1} 递增； health_status 根据
   * failureThreshold 自动判为 UNHEALTHY / DEGRADED，并直接返回本次递增后的计数。
   *
   * <p>{@link DispatchHealthUpsertCommand#nextProbeAt()} 在失败路径上承载"首次失败 INSERT 的 placeholder
   * 回退时间"（{@code firstFailureBackoffAt}），mapper XML 字段名保持不变。
   */
  public int upsertFailureAndBump(DispatchHealthUpsertCommand cmd) {
    Timestamp probeTime = toTimestamp(cmd.now());
    Timestamp firstFailureBackoffAt = toTimestamp(cmd.nextProbeAt());
    int minimumFailureThreshold = Math.max(1, cmd.failureThreshold());
    DispatchChannelHealthFailureParam params = DispatchChannelHealthFailureParam.builder()
        .tenantId(cmd.tenantId())
        .channelCode(cmd.channelCode())
        .channelType(cmd.channelType())
        .now(probeTime)
        .firstFailureBackoffAt(firstFailureBackoffAt)
        .failureThreshold(minimumFailureThreshold)
        .probeMessage(cmd.probeMessage())
        .probeEvidence(cmd.probeEvidence())
        .build();
    return mapper.upsertFailureAndBump(params);
  }

  public boolean recalcBackoff(DispatchHealthUpsertCommand cmd, int expectedFailures) {
    Timestamp probeTime = toTimestamp(cmd.now());
    // Citus:next_probe_at 由调用方(Java)按新 count 算好指数退避后传入,不再在 SQL 里
    // 用 power(2, consecutive_failures) 这类带列引用的函数(分布式 UPDATE 不允许)。
    Timestamp nextProbeTime = toTimestamp(cmd.nextProbeAt());
    DispatchChannelHealthBackoffParam params = new DispatchChannelHealthBackoffParam(
        cmd.tenantId(), cmd.channelCode(), probeTime, nextProbeTime, expectedFailures);
    return mapper.recalcBackoff(params) > 0;
  }

  public long countByHealthStatus(String healthStatus) {
    return mapper.countByHealthStatus(healthStatus);
  }

  public long countProbeOverdue(Instant now) {
    Timestamp probeTime = toTimestamp(now);
    return mapper.countProbeOverdue(probeTime);
  }

  public void upsertHealth(DispatchChannelHealthSnapshot s) {
    Timestamp lastProbeAt = toTimestamp(s.lastProbeAt());
    Timestamp lastSuccessAt = toTimestamp(s.lastSuccessAt());
    Timestamp lastFailureAt = toTimestamp(s.lastFailureAt());
    Timestamp nextProbeAt = toTimestamp(s.nextProbeAt());
    DispatchChannelHealthUpsertParam params = DispatchChannelHealthUpsertParam.builder()
        .tenantId(s.tenantId())
        .channelCode(s.channelCode())
        .channelType(s.channelType())
        .healthStatus(s.healthStatus())
        .consecutiveFailures(s.consecutiveFailures())
        .lastProbeAt(lastProbeAt)
        .lastSuccessAt(lastSuccessAt)
        .lastFailureAt(lastFailureAt)
        .nextProbeAt(nextProbeAt)
        .probeMessage(s.probeMessage())
        .probeEvidence(s.probeEvidence())
        .build();
    mapper.upsertHealth(params);
  }

  private DispatchChannelHealthSnapshot toSnapshot(DispatchChannelHealthView row) {
    int consecutiveFailures =
        EmptyChecks.isNull(row.consecutiveFailures()) ? 0 : row.consecutiveFailures();
    return new DispatchChannelHealthSnapshot(
        row.tenantId(),
        row.channelCode(),
        row.channelType(),
        row.healthStatus(),
        consecutiveFailures,
        row.lastProbeAt(),
        row.lastSuccessAt(),
        row.lastFailureAt(),
        row.nextProbeAt(),
        row.probeMessage(),
        row.probeEvidence());
  }

  private Timestamp toTimestamp(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }
}
