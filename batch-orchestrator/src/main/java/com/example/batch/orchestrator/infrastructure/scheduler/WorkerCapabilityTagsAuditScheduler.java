package com.example.batch.orchestrator.infrastructure.scheduler;

import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.rls.RlsTenantContextHolder;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.domain.param.InvalidCapabilityTagsParam;
import com.example.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import com.example.batch.orchestrator.mapper.WorkerRegistryMapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Worker {@code capability_tags} 数据质量审计调度器。
 *
 * <p>背景：{@link
 * com.example.batch.orchestrator.infrastructure.scheduler.DefaultWorkerSelector#capabilityTagsContain}
 * 对畸形 JSON 采取"WARN + 当作无能力"的降级策略，避免一条脏记录阻塞整条 selector 链路。但这会让 partition 反复命中"无能力 worker →
 * 跳过"而没有显眼的错误信号，运维侧可能长时间看不到源头。
 *
 * <p>本调度器以固定周期（默认 5 分钟，{@code batch.worker.audit.capability-tags-scan-interval-millis} 可覆盖）扫描
 * ONLINE / DRAINING 的 worker，筛出 {@code capability_tags} 不符合"字符串数组"约定的行：
 *
 * <ul>
 *   <li>DB 侧用 {@code jsonb_typeof(capability_tags) &lt;&gt; 'array'} 或含非字符串元素过滤，O(activeWorkers)
 *       扫表；
 *   <li>app 侧再用 {@link JsonUtils#fromJson} 做一次"真正按 selector 语义"的验证，保证 DB 过滤的行集合确实会被 selector 拒绝；
 *   <li>命中时 {@code WARN} 日志 + {@code batch.worker.capability_tags.invalid.count} gauge；
 *   <li>正常情况 gauge = 0，可在 Grafana 设告警阈值 "&gt; 0 持续 2 个周期"。
 * </ul>
 *
 * <p><b>集群并发</b>：ShedLock({@code worker_capability_tags_audit}, PT2M)；纯只读扫描，持锁远超预期。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkerCapabilityTagsAuditScheduler {

  private static final String METRIC_INVALID_COUNT = "batch.worker.capability_tags.invalid.count";

  private final WorkerRegistryMapper workerRegistryMapper;
  private final OrchestratorGracefulShutdown gracefulShutdown;
  private final MeterRegistry meterRegistry;

  private final AtomicLong invalidCount = new AtomicLong();

  @Value("${batch.worker.audit.capability-tags-log-sample-limit:10}")
  private int logSampleLimit;

  @PostConstruct
  void initializeMeters() {
    meterRegistry.gauge(METRIC_INVALID_COUNT, invalidCount);
  }

  @Scheduled(fixedDelayString = "${batch.worker.audit.capability-tags-scan-interval-millis:300000}")
  @SchedulerLock(
      name = "worker_capability_tags_audit",
      lockAtMostFor = "PT2M",
      lockAtLeastFor = "PT5S")
  public void auditCapabilityTags() {
    if (gracefulShutdown.isDraining()) {
      return;
    }
    List<InvalidCapabilityTagsParam> rows = workerRegistryMapper.selectInvalidCapabilityTags();
    if (rows == null || rows.isEmpty()) {
      invalidCount.set(0L);
      return;
    }
    int confirmed = 0;
    int logged = 0;
    for (InvalidCapabilityTagsParam row : rows) {
      String tenantId = row == null ? null : row.getTenantId();
      if (tenantId == null || tenantId.isBlank()) {
        // 没 tenantId 直接判脏（原 looksValidStringArray 逻辑保留）。
        if (!looksValidStringArray(row == null ? null : row.getRawValue())) {
          confirmed++;
        }
        continue;
      }
      // RLS Phase B：审计 loop 当前只跑 JSON 解析、未触 DB，但按统一规范仍以 worker 所属 tenant 绑定，
      // 让未来扩展（例如把脏行 mark 到 worker_registry.audit_invalid）天然落到正确 RLS 上下文。
      final int loggedSnapshot = logged;
      boolean invalid =
          RlsTenantContextHolder.runWithTenant(
              tenantId,
              () -> {
                if (!looksValidStringArray(row.getRawValue())) {
                  if (loggedSnapshot < logSampleLimit) {
                    log.warn(
                        "invalid capability_tags detected: tenant={} workerCode={} raw={}",
                        row.getTenantId(),
                        row.getWorkerCode(),
                        row.getRawValue());
                  }
                  return true;
                }
                return false;
              });
      if (invalid) {
        confirmed++;
        if (logged < logSampleLimit) {
          logged++;
        }
      }
    }
    invalidCount.set(confirmed);
    if (confirmed > logSampleLimit) {
      log.warn(
          "invalid capability_tags total={} (sampled first {} in logs above)",
          confirmed,
          logSampleLimit);
    }
  }

  /**
   * 严格校验"字符串数组"约定。必须用 {@link JsonNode} 而非 {@code String[].class}——Jackson 默认会把数值 元素静默强转成字符串（{@code
   * [1,2]} 不会报错），这恰恰就是要审计的"异常数据"。
   */
  private boolean looksValidStringArray(String raw) {
    if (raw == null || raw.isBlank()) {
      return true;
    }
    try {
      JsonNode node = JsonUtils.fromJson(raw, JsonNode.class);
      if (node == null || !node.isArray()) {
        return false;
      }
      for (JsonNode elem : node) {
        if (elem == null || !elem.isTextual()) {
          return false;
        }
      }
      return true;
    } catch (RuntimeException ex) {
      SwallowedExceptionLogger.warn(
          WorkerCapabilityTagsAuditScheduler.class, "catch:RuntimeException", ex);

      return false;
    }
  }
}
