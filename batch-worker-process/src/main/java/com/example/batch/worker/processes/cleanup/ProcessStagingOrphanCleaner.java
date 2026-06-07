package com.example.batch.worker.processes.cleanup;

import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.worker.processes.mapper.business.ProcessStagingMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
    // 先做分区维护(预建未来日分区 + DROP 过期日分区);失败不阻断后续 orphan 行清理。
    try {
      maintainPartitions();
    } catch (RuntimeException ex) {
      log.warn("process staging partition maintenance failed: {}", ex.getMessage());
    }
    cleanOnce();
  }

  private static final DateTimeFormatter PARTITION_YMD = DateTimeFormatter.ofPattern("yyyyMMdd");

  /**
   * 分区维护:确保 {@code [今天, 今天+preCreateDays]} 的天级分区存在,并 DROP 早于 {@code 今天-retentionDays} 的过期日分区。
   *
   * <p>DROP 整个日分区瞬间把空间还给 OS,是根治 staging 物理膨胀的关键(DELETE 不缩文件)。分区名 / 边界全部从 UTC 日期派生,无注入面。 {@code
   * retentionDays <= 0} 时跳过 DROP(不自动回收分区)。
   *
   * <p>包级可见,供单测直接调用。
   */
  void maintainPartitions() {
    int preCreateDays = Math.max(0, properties.getPreCreateDays());
    int retentionDays = properties.getRetentionDays();
    LocalDate today = LocalDate.ofInstant(BatchDateTimeSupport.utcNow(), ZoneOffset.UTC);

    int created = 0;
    for (int d = 0; d <= preCreateDays; d++) {
      LocalDate day = today.plusDays(d);
      String name = "process_staging_p" + day.format(PARTITION_YMD);
      String fromTs = day + " 00:00:00+00";
      String toTs = day.plusDays(1) + " 00:00:00+00";
      try {
        processStagingMapper.createDailyPartition(name, fromTs, toTs);
        created++;
      } catch (RuntimeException ex) {
        log.warn("create daily partition failed: name={}, cause={}", name, ex.getMessage());
      }
    }

    int dropped = 0;
    if (retentionDays > 0) {
      String cutoffYmd = today.minusDays(retentionDays).format(PARTITION_YMD);
      List<String> expired = processStagingMapper.listExpiredDailyPartitions(cutoffYmd);
      for (String partition : expired) {
        try {
          processStagingMapper.dropPartition(partition);
          dropped++;
        } catch (RuntimeException ex) {
          log.warn("drop expired partition failed: name={}, cause={}", partition, ex.getMessage());
        }
      }
    }

    if (created > 0 || dropped > 0) {
      log.info(
          "process staging partition maintenance: created={}, dropped={}, retentionDays={},"
              + " preCreateDays={}",
          created,
          dropped,
          retentionDays,
          preCreateDays);
    }
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
