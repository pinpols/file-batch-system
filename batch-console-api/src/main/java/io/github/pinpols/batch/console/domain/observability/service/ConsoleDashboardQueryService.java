package io.github.pinpols.batch.console.domain.observability.service;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.domain.observability.mapper.ConsoleDashboardQueryMapper;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.ActivePartitionView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.AlertTrendView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.DayCountView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.DaySeverityCountView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.DayStatusCountView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.ExecutionProgressView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.JobStatsView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.SeverityCountView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.SlaComplianceView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.SlaDayView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.SlaJobReportView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.SlaReportView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.SlaStatsView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.StatusCountView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.TenantUsageView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.TriggerStatsView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.TypeCountView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.WorkerGroupStatusCountView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.WorkerLoadView;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import io.github.pinpols.batch.console.support.cache.ConsoleQueryCacheService;
import java.math.BigDecimal;
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
    List<DayStatusCountView> dailyTrend = repository.jobDailyTrend(resolved, days);
    return new JobStatsView(byStatus, total, dailyTrend);
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
    List<TypeCountView> typeCounts = repository.triggerTypeCounts(resolved, days);
    List<DayCountView> dailyTrend = repository.triggerDailyTrend(resolved, days);
    return new TriggerStatsView(typeCounts, dailyTrend);
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
    List<StatusCountView> statusCounts = repository.workerStatusCounts(resolved);
    List<WorkerGroupStatusCountView> workerGroupStatusCounts =
        repository.workerGroupStatusCounts(resolved);
    List<ActivePartitionView> activePartitions = repository.activePartitionsByWorker(resolved);
    return new WorkerLoadView(statusCounts, workerGroupStatusCounts, activePartitions);
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
    List<SeverityCountView> severityCounts = repository.alertSeverityCounts(resolved, days);
    List<DaySeverityCountView> dailyTrend = repository.alertDailyTrend(resolved, days);
    return new AlertTrendView(severityCounts, dailyTrend);
  }

  public List<ExecutionProgressView> executionProgress(
      String tenantId, String jobCode, String bizDate) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    List<ExecutionProgressView> progressRows =
        repository.executionProgress(resolved, jobCode, bizDate);
    return progressRows.stream().map(ExecutionProgressView::withDerivedProgress).toList();
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
    long jobDefinitionCount = nullToZero(repository.countJobDefinitions(resolved));
    long workflowDefinitionCount = nullToZero(repository.countWorkflowDefinitions(resolved));
    long fileChannelCount = nullToZero(repository.countFileChannels(resolved));
    long fileTemplateCount = nullToZero(repository.countFileTemplates(resolved));
    long recentJobInstanceCount = nullToZero(repository.countRecentJobInstances(resolved, days));
    long recentFileCount = nullToZero(repository.countRecentFiles(resolved, days));
    return new TenantUsageView(
        resolved,
        jobDefinitionCount,
        workflowDefinitionCount,
        fileChannelCount,
        fileTemplateCount,
        recentJobInstanceCount,
        recentFileCount,
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
    List<SlaJobReportView> jobs = repository.slaJobReport(resolved, days);
    return new SlaReportView(resolved, days, jobs);
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
    Long breachedCount = EmptyChecks.isNull(stats) ? null : stats.breached();
    Long breached = EmptyChecks.isNull(breachedCount) ? 0L : breachedCount;
    Long onTimeCount = EmptyChecks.isNull(stats) ? null : stats.onTime();
    Long onTime = EmptyChecks.isNull(onTimeCount) ? 0L : onTimeCount;
    Long totalWithSlaCount = EmptyChecks.isNull(stats) ? null : stats.totalWithSla();
    Long totalWithSla = EmptyChecks.isNull(totalWithSlaCount) ? 0L : totalWithSlaCount;
    BigDecimal avgDurationSeconds = EmptyChecks.isNull(stats) ? null : stats.avgDurationSeconds();
    List<SlaDayView> dailyTrend = repository.slaDailyTrend(resolved, days);
    return new SlaComplianceView(breached, onTime, totalWithSla, avgDurationSeconds, dailyTrend);
  }

  private static String cacheTenant(String tenantId) {
    return ConsoleQueryCacheService.keySegment(tenantId);
  }
}
