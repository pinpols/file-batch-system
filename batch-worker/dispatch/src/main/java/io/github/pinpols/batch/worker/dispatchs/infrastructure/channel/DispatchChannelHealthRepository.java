package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.dispatchs.mapper.DispatchChannelHealthMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** 分发渠道健康状态持久化仓库。 */
@Repository
@RequiredArgsConstructor
public class DispatchChannelHealthRepository {

  // PMD AvoidDuplicateLiterals 触发：mapper 参数 map key 在多个方法里重复，提常量统一管理
  private static final String P_TENANT_ID = "tenantId";
  private static final String P_CHANNEL_CODE = "channelCode";
  private static final String P_CHANNEL_TYPE = "channelType";
  private static final String P_NOW = "now";
  private static final String P_NEXT_PROBE_AT = "nextProbeAt";
  private static final String P_PROBE_MESSAGE = "probeMessage";
  private static final String P_PROBE_EVIDENCE = "probeEvidence";

  private final DispatchChannelHealthMapper mapper;

  public List<Map<String, Object>> findEnabledProbeChannels(List<String> types, int limit) {
    if (types == null || types.isEmpty()) {
      return List.of();
    }
    return mapper.findEnabledProbeChannels(types, limit);
  }

  public DispatchChannelHealthSnapshot findHealth(String tenantId, String channelCode) {
    Map<String, Object> row = mapper.findHealth(tenantId, channelCode);
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
    Map<String, Object> params = new HashMap<>();
    params.put(P_TENANT_ID, tenantId);
    params.put(P_CHANNEL_CODE, channelCode);
    Timestamp probeTime = toTimestamp(now);
    params.put(P_NOW, probeTime);
    Timestamp nextProbeTime = toTimestamp(newNextProbeAt);
    params.put("newNextProbeAt", nextProbeTime);
    return mapper.tryClaimHalfOpenProbe(params) > 0;
  }

  /**
   * P2：原子写入"成功"结果，等价于旧 upsertHealth(HEALTHY, failures=0, ...) 但 SQL 侧一次 完成，无 find-then-upsert 竞态。
   */
  public void upsertSuccess(DispatchHealthUpsertCommand cmd) {
    Map<String, Object> params = new HashMap<>();
    params.put(P_TENANT_ID, cmd.tenantId());
    params.put(P_CHANNEL_CODE, cmd.channelCode());
    params.put(P_CHANNEL_TYPE, cmd.channelType());
    Timestamp probeTime = toTimestamp(cmd.now());
    params.put(P_NOW, probeTime);
    Timestamp nextProbeTime = toTimestamp(cmd.nextProbeAt());
    params.put(P_NEXT_PROBE_AT, nextProbeTime);
    params.put(P_PROBE_MESSAGE, cmd.probeMessage());
    params.put(P_PROBE_EVIDENCE, cmd.probeEvidence());
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
    Map<String, Object> params = new HashMap<>();
    params.put(P_TENANT_ID, cmd.tenantId());
    params.put(P_CHANNEL_CODE, cmd.channelCode());
    params.put(P_CHANNEL_TYPE, cmd.channelType());
    Timestamp probeTime = toTimestamp(cmd.now());
    params.put(P_NOW, probeTime);
    Timestamp firstFailureBackoffAt = toTimestamp(cmd.nextProbeAt());
    params.put("firstFailureBackoffAt", firstFailureBackoffAt);
    int minimumFailureThreshold = Math.max(1, cmd.failureThreshold());
    params.put("failureThreshold", minimumFailureThreshold);
    params.put(P_PROBE_MESSAGE, cmd.probeMessage());
    params.put(P_PROBE_EVIDENCE, cmd.probeEvidence());
    return mapper.upsertFailureAndBump(params);
  }

  public boolean recalcBackoff(DispatchHealthUpsertCommand cmd, int expectedFailures) {
    Map<String, Object> params = new HashMap<>();
    params.put(P_TENANT_ID, cmd.tenantId());
    params.put(P_CHANNEL_CODE, cmd.channelCode());
    Timestamp probeTime = toTimestamp(cmd.now());
    params.put(P_NOW, probeTime);
    // Citus:next_probe_at 由调用方(Java)按新 count 算好指数退避后传入,不再在 SQL 里
    // 用 power(2, consecutive_failures) 这类带列引用的函数(分布式 UPDATE 不允许)。
    Timestamp nextProbeTime = toTimestamp(cmd.nextProbeAt());
    params.put("newNextProbeAt", nextProbeTime);
    params.put("expectedFailures", expectedFailures);
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
    Map<String, Object> params = new HashMap<>();
    params.put(P_TENANT_ID, s.tenantId());
    params.put(P_CHANNEL_CODE, s.channelCode());
    params.put(P_CHANNEL_TYPE, s.channelType());
    params.put("healthStatus", s.healthStatus());
    params.put("consecutiveFailures", s.consecutiveFailures());
    Timestamp lastProbeAt = toTimestamp(s.lastProbeAt());
    params.put("lastProbeAt", lastProbeAt);
    Timestamp lastSuccessAt = toTimestamp(s.lastSuccessAt());
    params.put("lastSuccessAt", lastSuccessAt);
    Timestamp lastFailureAt = toTimestamp(s.lastFailureAt());
    params.put("lastFailureAt", lastFailureAt);
    Timestamp nextProbeAt = toTimestamp(s.nextProbeAt());
    params.put(P_NEXT_PROBE_AT, nextProbeAt);
    params.put(P_PROBE_MESSAGE, s.probeMessage());
    params.put(P_PROBE_EVIDENCE, s.probeEvidence());
    mapper.upsertHealth(params);
  }

  private DispatchChannelHealthSnapshot toSnapshot(Map<String, Object> row) {
    String tenantId = stringValue(row.get("tenant_id"));
    String channelCode = stringValue(row.get("channel_code"));
    String channelType = stringValue(row.get("channel_type"));
    String healthStatus = stringValue(row.get("health_status"));
    int consecutiveFailures = intValue(row.get("consecutive_failures"));
    Instant lastProbeAt = instantValue(row.get("last_probe_at"));
    Instant lastSuccessAt = instantValue(row.get("last_success_at"));
    Instant lastFailureAt = instantValue(row.get("last_failure_at"));
    Instant nextProbeAt = instantValue(row.get("next_probe_at"));
    String probeMessage = stringValue(row.get("probe_message"));
    String probeEvidence = stringValue(row.get("probe_evidence"));
    return new DispatchChannelHealthSnapshot(
        tenantId,
        channelCode,
        channelType,
        healthStatus,
        consecutiveFailures,
        lastProbeAt,
        lastSuccessAt,
        lastFailureAt,
        nextProbeAt,
        probeMessage,
        probeEvidence);
  }

  private Timestamp toTimestamp(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private String stringValue(Object value) {
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value);
    return Texts.hasText(text) && !"null".equalsIgnoreCase(text) ? text : null;
  }

  private int intValue(Object value) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value == null) {
      return 0;
    }
    String rawText = String.valueOf(value);
    String text = rawText.trim();
    return text.isEmpty() ? 0 : Integer.parseInt(text);
  }

  private Instant instantValue(Object value) {
    if (value instanceof Timestamp ts) {
      return ts.toInstant();
    }
    if (value instanceof Date date) {
      return date.toInstant();
    }
    if (value == null) {
      return null;
    }
    String rawText = String.valueOf(value);
    String text = rawText.trim();
    return text.isEmpty() ? null : Instant.parse(text);
  }
}
