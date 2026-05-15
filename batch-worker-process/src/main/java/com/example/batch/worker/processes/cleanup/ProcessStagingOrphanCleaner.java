package com.example.batch.worker.processes.cleanup;

import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.worker.processes.mapper.business.ProcessStagingMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * P1-7:`batch.process_staging` 孤儿清理调度器。
 *
 * <p>清理对象:
 *
 * <ul>
 *   <li>VALIDATE 失败保留的 staging 行(超过 {@code retentionHours} 仍未被下次 PREPARE 同 batchKey 覆盖)
 *   <li>worker 在 COMMIT 后崩溃 / FEEDBACK 失败导致的 staging 残留(本批已 publish 但 staging 没清)
 *   <li>历史人工补数 / 测试数据残留
 * </ul>
 *
 * <p>策略:每 {@code interval} 跑一次,删 {@code staged_at < now() - retentionHours} 的行,单次最多删 {@code
 * batchSize}。多 worker 实例并行用 ShedLock 互斥(只允许一个 worker 执行)。
 *
 * <p>指标:
 *
 * <ul>
 *   <li>{@code batch.worker.process.staging.orphan.cleaned.total} —— 累计清理行数(Counter，dot-namespace
 *       全栈对齐)
 *   <li>{@code batch.worker.process.staging.oldest.age.seconds} —— 当前 staging 中最老一行的年龄(Gauge,运维巡检)
 * </ul>
 *
 * <p>关闭方式:`batch.worker.process.staging-cleanup.enabled=false`(默认 true)。
 */
@Slf4j
@Component
@Configuration
@EnableConfigurationProperties(ProcessStagingCleanupProperties.class)
@ConditionalOnProperty(
    name = "batch.worker.process.staging-cleanup.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ProcessStagingOrphanCleaner {

  private final ProcessStagingMapper processStagingMapper;
  private final ProcessStagingCleanupProperties properties;
  private final Counter cleanedCounter;

  public ProcessStagingOrphanCleaner(
      ProcessStagingMapper processStagingMapper,
      ProcessStagingCleanupProperties properties,
      ObjectProvider<MeterRegistry> meterRegistryProvider) {
    this.processStagingMapper = processStagingMapper;
    this.properties = properties;
    MeterRegistry registry = meterRegistryProvider.getIfAvailable();
    this.cleanedCounter =
        registry == null
            ? null
            // R7-A6-P2：dot-namespace 与全栈 (batch.worker.* / batch.outbox.*) 对齐；
            // 原 snake_case 名 Prometheus 还能识别但与项目惯例脱节。
            : Counter.builder("batch.worker.process.staging.orphan.cleaned.total")
                .description("累计被 ProcessStagingOrphanCleaner 删除的孤儿 staging 行数")
                .register(registry);
    if (registry != null) {
      registry.gauge(
          "batch.worker.process.staging.oldest.age.seconds",
          Collections.emptyList(),
          this,
          ProcessStagingOrphanCleaner::oldestAgeSecondsForGauge);
    }
  }

  /** 调度入口。fixed-delay 取自配置,默认 15 分钟。多实例并发由 ShedLock 互斥。 */
  @Scheduled(fixedDelayString = "${batch.worker.process.staging-cleanup.interval:PT15M}")
  @SchedulerLock(
      name = "process_staging_orphan_cleanup",
      lockAtMostFor = "PT5M",
      lockAtLeastFor = "PT30S")
  public void scheduledClean() {
    cleanOnce();
  }

  /**
   * 执行一次清理。返回实际删除行数,供测试 / 运维一次性调用使用。
   *
   * <p>语义:删 `staged_at < now() - INTERVAL retentionHours hours`,LIMIT batchSize。一次 tick 不 drain
   * 完;下次 tick 继续清,避免大表锁定时间过长。
   */
  public int cleanOnce() {
    int retentionHours = Math.max(1, properties.getRetentionHours());
    int batchSize = Math.max(100, properties.getBatchSize());
    int deleted = processStagingMapper.deleteOrphansOlderThan(retentionHours, batchSize);
    if (deleted > 0) {
      log.info(
          "process staging orphan cleanup: deleted={}, retentionHours={}, batchSize={}",
          deleted,
          retentionHours,
          batchSize);
      if (cleanedCounter != null) {
        cleanedCounter.increment(deleted);
      }
    }
    return deleted;
  }

  /** Gauge 回调:返回当前 staging 最老行的秒龄;表空时返 0。SQL 异常时返 -1 让运维察觉。 */
  double oldestAgeSecondsForGauge() {
    try {
      Optional<Instant> oldest = Optional.ofNullable(processStagingMapper.selectMinStagedAt());
      return oldest
          .map(
              at ->
                  Math.max(
                      0d,
                      (BatchDateTimeSupport.utcNow().toEpochMilli() - at.toEpochMilli()) / 1000d))
          .orElse(0d);
    } catch (RuntimeException ex) {
      log.warn("oldest staging age gauge query failed: {}", ex.getMessage());
      return -1d;
    }
  }
}
