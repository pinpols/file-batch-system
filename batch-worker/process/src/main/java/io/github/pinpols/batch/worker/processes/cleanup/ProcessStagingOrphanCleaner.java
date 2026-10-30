package io.github.pinpols.batch.worker.processes.cleanup;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.worker.processes.mapper.business.ProcessStagingMapper;
import io.github.pinpols.batch.worker.processes.mapper.platform.ProcessInvocationFenceMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * P1-7:`batch.process_staging` 孤儿清理调度器。
 *
 * <p>按代际创建时间选出超过 {@code retentionHours} 的候选，持有平台实例围栏确认候选不再是任务当前代际，
 * 再在业务库封存并分批删除该代际的暂存行，每次最多删除 {@code batchSize} 行。仅凭行的暂存时间不能判定可回收。
 * 调度入口使用 ShedLock 协调多实例。
 *
 * <p>指标:
 *
 * <ul>
 *   <li>{@code batch.worker.process.staging.orphan.cleaned.total} —— 累计清理行数(Counter，dot-namespace
 *       全栈对齐)
 *   <li>{@code batch.worker.process.staging.oldest.age.seconds} —— 当前 staging 中最老一行的年龄(Gauge,运维巡检)
 * </ul>
 *
 * <p>`enabled` 控制调度器，`deletion-enabled` 单独控制破坏性清理；后者在跨库恢复围栏验收前默认关闭。
 */
@Slf4j
@Component
@ConditionalOnProperty(
    name = "batch.worker.process.staging-cleanup.enabled",
    havingValue = "true",
    matchIfMissing = true)
@SuppressWarnings(
    "SpringJavaInjectionPointsAutowiringInspection") // Mapper 由应用启动类的业务库 @MapperScan 注册。
public class ProcessStagingOrphanCleaner {

  private final ProcessStagingMapper processStagingMapper;
  private final ProcessStagingCleanupProperties properties;
  private final Counter cleanedCounter;
  private final ProcessInvocationFenceMapper platformMapper;
  private final ProcessStagingBusinessCleanupService businessCleanup;
  private final ProcessStagingGenerationCleanupFence cleanupFence;
  private volatile double oldestAgeSeconds = -1d;

  @Autowired
  public ProcessStagingOrphanCleaner(
      ProcessStagingMapper processStagingMapper,
      ProcessStagingCleanupProperties properties,
      ObjectProvider<MeterRegistry> meterRegistryProvider,
      ProcessInvocationFenceMapper platformMapper,
      ProcessStagingBusinessCleanupService businessCleanup,
      ProcessStagingGenerationCleanupFence cleanupFence) {
    this.processStagingMapper = processStagingMapper;
    this.properties = properties;
    this.platformMapper = platformMapper;
    this.businessCleanup = businessCleanup;
    this.cleanupFence = cleanupFence;
    MeterRegistry registry = meterRegistryProvider.getIfAvailable();
    this.cleanedCounter = EmptyChecks.isNull(registry)
        ? null
        // R7-A6-P2：dot-namespace 与全栈 (batch.worker.* / batch.outbox.*) 对齐；
        // 原 snake_case 名 Prometheus 还能识别但与项目惯例脱节。
        : Counter.builder("batch.worker.process.staging.orphan.cleaned.total")
            .description("累计被 ProcessStagingOrphanCleaner 删除的孤儿 staging 行数")
            .register(registry);
    if (registry != null) { // empty-check: allow - optional metrics
      registry.gauge(
          "batch.worker.process.staging.oldest.age.seconds",
          Collections.emptyList(),
          this,
          ProcessStagingOrphanCleaner::oldestAgeSecondsForGauge);
    }
  }

  ProcessStagingOrphanCleaner(
      ProcessStagingMapper processStagingMapper,
      ProcessStagingCleanupProperties properties,
      ObjectProvider<MeterRegistry> meterRegistryProvider) {
    this(processStagingMapper, properties, meterRegistryProvider, null, null, null);
  }

  /** 调度入口。fixed-delay 取自配置,默认 15 分钟。多实例并发由 ShedLock 互斥。 */
  @Scheduled(fixedDelayString = "${batch.worker.process.staging-cleanup.interval:PT15M}")
  @SchedulerLock(
      name = "process_staging_orphan_cleanup",
      lockAtMostFor = "PT5M",
      lockAtLeastFor = "PT30S")
  public void scheduledClean() {
    // 先预建未来日分区;失败不阻断 generation 回收。
    try {
      maintainPartitions();
    } catch (RuntimeException ex) {
      log.warn(
          "process staging partition maintenance failed: {}", SwallowedExceptionLogger.summary(ex));
    }
    cleanOnce();
    refreshOldestAgeGauge();
  }

  private static final DateTimeFormatter PARTITION_YMD = DateTimeFormatter.ofPattern("yyyyMMdd");

  /**
   * 分区维护:确保 {@code [今天, 今天+preCreateDays]} 的天级分区存在。
   *
   * <p>分区包含多个 staging generation。仅凭分区日期无法证明所有 generation 均不可恢复，
   * 因此此处只预建分区；过期分区删除需单独实现并验证整分区生命周期围栏。
   *
   * <p>包级可见,供单测直接调用。
   */
  void maintainPartitions() {
    int preCreateDays = Math.max(0, properties.getPreCreateDays());
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
        log.warn(
            "create daily partition failed: name={}, cause={}",
            name,
            SwallowedExceptionLogger.summary(ex));
      }
    }

    if (created > 0) {
      log.info(
          "process staging partition maintenance: created={}, preCreateDays={}",
          created,
          preCreateDays);
    }
  }

  /**
   * 执行一次清理。返回实际删除行数,供测试 / 运维一次性调用使用。
   *
   * <p>候选 generation 必须先由平台库证明已被当前 task generation 替代，并在同一 JobInstance advisory fence
   * 内执行。业务库再原子封存 ledger 并有界删除精确 generation 行；保留最后一个 current generation。
   */
  public int cleanOnce() {
    if (!properties.isDeletionEnabled()) {
      return 0;
    }
    int retentionHours = Math.max(1, properties.getRetentionHours());
    int batchSize = Math.max(100, properties.getBatchSize());
    boolean stagingCleanupDependenciesAreUnavailable = EmptyChecks.isNull(platformMapper)
        || EmptyChecks.isNull(businessCleanup)
        || EmptyChecks.isNull(cleanupFence);
    if (stagingCleanupDependenciesAreUnavailable) {
      throw new IllegalStateException("PROCESS generation cleanup dependencies are not configured");
    }
    int deleted = 0;
    for (String tenantId : platformMapper.listCleanupTenants()) {
      Instant cursorTime = null;
      String cursorGenerationId = null;
      while (deleted < batchSize) {
        List<ProcessStagingGenerationCandidate> candidates = businessCleanup.listExpiredAfter(
            tenantId, retentionHours, batchSize, cursorTime, cursorGenerationId);
        if (EmptyChecks.isEmpty(Objects.requireNonNull(candidates))) {
          break;
        }
        for (ProcessStagingGenerationCandidate candidate : candidates) {
          if (deleted >= batchSize) {
            break;
          }
          try {
            deleted += cleanupFence.reclaimIfStale(candidate, batchSize - deleted);
          } catch (RuntimeException ex) {
            log.warn(
                "process staging generation cleanup failed: tenant={}, task={}, generation={}, cause={}",
                candidate.getTenantId(),
                candidate.getTaskId(),
                candidate.getGenerationId(),
                SwallowedExceptionLogger.summary(ex));
          }
        }
        ProcessStagingGenerationCandidate lastCandidate = candidates.getLast();
        cursorTime = Objects.requireNonNull(lastCandidate.getCreatedAt());
        cursorGenerationId = Objects.requireNonNull(lastCandidate.getGenerationId());
        if (candidates.size() < batchSize) {
          break;
        }
      }
      if (deleted >= batchSize) {
        break;
      }
    }
    if (deleted > 0) {
      log.info(
          "process staging orphan cleanup: deleted={}, retentionHours={}, batchSize={}",
          deleted,
          retentionHours,
          batchSize);
      if (EmptyChecks.isNotNull(cleanedCounter)) {
        cleanedCounter.increment(deleted);
      }
    }
    return deleted;
  }

  /** Gauge 回调只返回最近一次 RLS-scoped 后台扫描的结果，不在 scrape 期间直查数据库。 */
  double oldestAgeSecondsForGauge() {
    return oldestAgeSeconds;
  }

  private void refreshOldestAgeGauge() {
    try {
      if (EmptyChecks.isNull(platformMapper) || EmptyChecks.isNull(businessCleanup)) {
        oldestAgeSeconds = -1d;
        return;
      }
      Instant oldest = null;
      for (String tenantId : platformMapper.listCleanupTenants()) {
        Instant tenantOldest = businessCleanup.selectOldestStagedAt(tenantId, 500);
        boolean tenantHasEarlierStagedRow = EmptyChecks.isNotNull(tenantOldest)
            && (EmptyChecks.isNull(oldest) || tenantOldest.isBefore(oldest));
        if (tenantHasEarlierStagedRow) {
          oldest = tenantOldest;
        }
      }
      if (EmptyChecks.isNull(oldest)) {
        oldestAgeSeconds = 0d;
      } else {
        Instant sampledAt = BatchDateTimeSupport.utcNow();
        long ageMillis = sampledAt.toEpochMilli() - oldest.toEpochMilli();
        oldestAgeSeconds = Math.max(0d, ageMillis / 1000d);
      }
    } catch (RuntimeException ex) {
      log.warn("oldest staging age gauge query failed: {}", SwallowedExceptionLogger.summary(ex));
      oldestAgeSeconds = -1d;
    }
  }
}
