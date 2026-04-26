package com.example.batch.worker.dispatchs.infrastructure.channel;

import com.example.batch.common.utils.Texts;
import com.example.batch.worker.dispatchs.mapper.DispatchChannelHealthMapper;
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
    params.put("tenantId", tenantId);
    params.put("channelCode", channelCode);
    params.put("now", toTimestamp(now));
    params.put("newNextProbeAt", toTimestamp(newNextProbeAt));
    return mapper.tryClaimHalfOpenProbe(params) > 0;
  }

  /**
   * P2：原子写入"成功"结果，等价于旧 upsertHealth(HEALTHY, failures=0, ...) 但 SQL 侧一次 完成，无 find-then-upsert 竞态。
   */
  public void upsertSuccess(DispatchHealthUpsertCommand cmd) {
    Map<String, Object> params = new HashMap<>();
    params.put("tenantId", cmd.tenantId());
    params.put("channelCode", cmd.channelCode());
    params.put("channelType", cmd.channelType());
    params.put("now", toTimestamp(cmd.now()));
    params.put("nextProbeAt", toTimestamp(cmd.nextProbeAt()));
    params.put("probeMessage", cmd.probeMessage());
    params.put("probeEvidence", cmd.probeEvidence());
    mapper.upsertSuccess(params);
  }

  /**
   * P2：原子写入"失败"结果。consecutive_failures 由 SQL {@code COALESCE(...) + 1} 递增； health_status 根据
   * failureThreshold 自动判为 UNHEALTHY / DEGRADED。随后调用 {@link #recalcBackoff} 按新的 count 更新
   * next_probe_at，完成指数退避。
   *
   * <p>{@link DispatchHealthUpsertCommand#nextProbeAt()} 在失败路径上承载"首次失败 INSERT 的 placeholder
   * 回退时间"（{@code firstFailureBackoffAt}），mapper XML 字段名保持不变。
   */
  public void upsertFailureAndBump(DispatchHealthUpsertCommand cmd) {
    Map<String, Object> params = new HashMap<>();
    params.put("tenantId", cmd.tenantId());
    params.put("channelCode", cmd.channelCode());
    params.put("channelType", cmd.channelType());
    params.put("now", toTimestamp(cmd.now()));
    params.put("firstFailureBackoffAt", toTimestamp(cmd.nextProbeAt()));
    params.put("failureThreshold", Math.max(1, cmd.failureThreshold()));
    params.put("probeMessage", cmd.probeMessage());
    params.put("probeEvidence", cmd.probeEvidence());
    mapper.upsertFailureAndBump(params);
  }

  public void recalcBackoff(DispatchHealthUpsertCommand cmd) {
    Map<String, Object> params = new HashMap<>();
    params.put("tenantId", cmd.tenantId());
    params.put("channelCode", cmd.channelCode());
    params.put("now", toTimestamp(cmd.now()));
    params.put("probeIntervalMillis", cmd.probeIntervalMillis());
    params.put("maxBackoffMillis", cmd.maxBackoffMillis());
    mapper.recalcBackoff(params);
  }

  public void upsertHealth(DispatchChannelHealthSnapshot s) {
    Map<String, Object> params = new HashMap<>();
    params.put("tenantId", s.tenantId());
    params.put("channelCode", s.channelCode());
    params.put("channelType", s.channelType());
    params.put("healthStatus", s.healthStatus());
    params.put("consecutiveFailures", s.consecutiveFailures());
    params.put("lastProbeAt", toTimestamp(s.lastProbeAt()));
    params.put("lastSuccessAt", toTimestamp(s.lastSuccessAt()));
    params.put("lastFailureAt", toTimestamp(s.lastFailureAt()));
    params.put("nextProbeAt", toTimestamp(s.nextProbeAt()));
    params.put("probeMessage", s.probeMessage());
    params.put("probeEvidence", s.probeEvidence());
    mapper.upsertHealth(params);
  }

  private DispatchChannelHealthSnapshot toSnapshot(Map<String, Object> row) {
    return new DispatchChannelHealthSnapshot(
        stringValue(row.get("tenant_id")),
        stringValue(row.get("channel_code")),
        stringValue(row.get("channel_type")),
        stringValue(row.get("health_status")),
        intValue(row.get("consecutive_failures")),
        instantValue(row.get("last_probe_at")),
        instantValue(row.get("last_success_at")),
        instantValue(row.get("last_failure_at")),
        instantValue(row.get("next_probe_at")),
        stringValue(row.get("probe_message")),
        stringValue(row.get("probe_evidence")));
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
    String text = String.valueOf(value).trim();
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
    String text = String.valueOf(value).trim();
    return text.isEmpty() ? null : Instant.parse(text);
  }
}
