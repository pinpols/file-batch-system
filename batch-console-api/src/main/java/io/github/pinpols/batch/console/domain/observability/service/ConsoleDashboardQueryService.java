package io.github.pinpols.batch.console.domain.observability.service;

import io.github.pinpols.batch.console.domain.observability.mapper.ConsoleDashboardQueryMapper;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.AlertTrendView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.ExecutionProgressView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.JobStatsView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.SlaComplianceView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.SlaReportView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.SlaStatsView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.StatusCountView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.TenantUsageView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.TriggerStatsView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.WorkerLoadView;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import io.github.pinpols.batch.console.support.cache.ConsoleQueryCacheService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ConsoleDashboardQueryService {

  private static final String DASHBOARD_CACHE_PREFIX = "dashboard:";
  // null status 占位，保持历史 dashboard wire 兼容。
  private static final String UNKNOWN = "UNKNOWN";

  private final ConsoleDashboardQueryMapper repository;
  private final TenantIdResolver tenantGuard;
  private final ConsoleQueryCacheService cacheService;

  public ConsoleDashboardQueryService(
      ConsoleDashboardQueryMapper repository,
      TenantIdResolver tenantGuard,
      ConsoleQueryCacheService cacheService) {
    this.repository = repository;
    this.tenantGuard = tenantGuard;
    this.cacheService = cacheService;
  }

  public JobStatsView jobStats(String tenantId, int days) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return cacheService.getOrLoad(
        DASHBOARD_CACHE_PREFIX + cacheTenant(resolved) + ":job-stats:" + days,
        ConsoleQueryCacheService.DASHBOARD_TTL,
        JobStatsView.class,
        () -> loadJobStats(resolved, days));
  }

  private JobStatsView loadJobStats(String resolved, int days) {
    Map<String, Long> byStatus = new LinkedHashMap<>();
    long total = 0L;
    for (StatusCountView row : repository.jobStatusCounts(resolved, days)) {
      long count = row.count() == null ? 0L : row.count();
      byStatus.put(row.status() == null ? UNKNOWN : row.status(), count);
      total += count;
    }
    return new JobStatsView(byStatus, total, repository.jobDailyTrend(resolved, days));
  }

  public TriggerStatsView triggerStats(String tenantId, int days) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return cacheService.getOrLoad(
        DASHBOARD_CACHE_PREFIX + cacheTenant(resolved) + ":trigger-stats:" + days,
        ConsoleQueryCacheService.DASHBOARD_TTL,
        TriggerStatsView.class,
        () -> loadTriggerStats(resolved, days));
  }

  private TriggerStatsView loadTriggerStats(String resolved, int days) {
    return new TriggerStatsView(
        repository.triggerTypeCounts(resolved, days), repository.triggerDailyTrend(resolved, days));
  }

  public WorkerLoadView workerLoad(String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return cacheService.getOrLoad(
        DASHBOARD_CACHE_PREFIX + cacheTenant(resolved) + ":worker-load",
        ConsoleQueryCacheService.DASHBOARD_TTL,
        WorkerLoadView.class,
        () -> loadWorkerLoad(resolved));
  }

  private WorkerLoadView loadWorkerLoad(String resolved) {
    return new WorkerLoadView(
        repository.workerStatusCounts(resolved),
        repository.workerGroupStatusCounts(resolved),
        repository.activePartitionsByWorker(resolved));
  }

  public AlertTrendView alertTrend(String tenantId, int days) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return cacheService.getOrLoad(
        DASHBOARD_CACHE_PREFIX + cacheTenant(resolved) + ":alert-trend:" + days,
        ConsoleQueryCacheService.DASHBOARD_TTL,
        AlertTrendView.class,
        () -> loadAlertTrend(resolved, days));
  }

  private AlertTrendView loadAlertTrend(String resolved, int days) {
    return new AlertTrendView(
        repository.alertSeverityCounts(resolved, days), repository.alertDailyTrend(resolved, days));
  }

  public List<ExecutionProgressView> executionProgress(
      String tenantId, String jobCode, String bizDate) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return repository.executionProgress(resolved, jobCode, bizDate).stream()
        .map(ExecutionProgressView::withDerivedProgress)
        .toList();
  }

  public TenantUsageView tenantUsage(String tenantId, int days) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return cacheService.getOrLoad(
        DASHBOARD_CACHE_PREFIX + cacheTenant(resolved) + ":tenant-usage:" + days,
        ConsoleQueryCacheService.DASHBOARD_TTL,
        TenantUsageView.class,
        () -> loadTenantUsage(resolved, days));
  }

  private TenantUsageView loadTenantUsage(String resolved, int days) {
    return new TenantUsageView(
        resolved,
        nullToZero(repository.countJobDefinitions(resolved)),
        nullToZero(repository.countWorkflowDefinitions(resolved)),
        nullToZero(repository.countFileChannels(resolved)),
        nullToZero(repository.countFileTemplates(resolved)),
        nullToZero(repository.countRecentJobInstances(resolved, days)),
        nullToZero(repository.countRecentFiles(resolved, days)),
        days);
  }

  private long nullToZero(Long value) {
    return value == null ? 0L : value;
  }

  public SlaReportView slaReport(String tenantId, int days) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return cacheService.getOrLoad(
        DASHBOARD_CACHE_PREFIX + cacheTenant(resolved) + ":sla-report:" + days,
        ConsoleQueryCacheService.DASHBOARD_TTL,
        SlaReportView.class,
        () -> loadSlaReport(resolved, days));
  }

  private SlaReportView loadSlaReport(String resolved, int days) {
    return new SlaReportView(resolved, days, repository.slaJobReport(resolved, days));
  }

  public SlaComplianceView slaCompliance(String tenantId, int days) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return cacheService.getOrLoad(
        DASHBOARD_CACHE_PREFIX + cacheTenant(resolved) + ":sla-compliance:" + days,
        ConsoleQueryCacheService.DASHBOARD_TTL,
        SlaComplianceView.class,
        () -> loadSlaCompliance(resolved, days));
  }

  private SlaComplianceView loadSlaCompliance(String resolved, int days) {
    SlaStatsView stats = repository.slaStats(resolved, days);
    return new SlaComplianceView(
        stats == null || stats.breached() == null ? 0L : stats.breached(),
        stats == null || stats.onTime() == null ? 0L : stats.onTime(),
        stats == null || stats.totalWithSla() == null ? 0L : stats.totalWithSla(),
        stats == null ? null : stats.avgDurationSeconds(),
        repository.slaDailyTrend(resolved, days));
  }

  private static String cacheTenant(String tenantId) {
    return ConsoleQueryCacheService.keySegment(tenantId);
  }
}
