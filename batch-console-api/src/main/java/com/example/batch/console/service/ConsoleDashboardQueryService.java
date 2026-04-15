package com.example.batch.console.service;

import com.example.batch.console.repository.ConsoleDashboardQueryRepository;
import com.example.batch.console.support.ConsoleTenantGuard;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ConsoleDashboardQueryService {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String KEY_DAILY_TREND = "dailyTrend";
  private static final String KEY_DAY = "day";
  private static final String KEY_COUNT = "count";

  private final ConsoleDashboardQueryRepository repository;
  private final ConsoleTenantGuard tenantGuard;

  public ConsoleDashboardQueryService(
      ConsoleDashboardQueryRepository repository, ConsoleTenantGuard tenantGuard) {
    this.repository = repository;
    this.tenantGuard = tenantGuard;
  }

  public Map<String, Object> jobStats(String tenantId, int days) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    Map<String, Object> result = new LinkedHashMap<>();
    Map<String, Long> byStatus = new LinkedHashMap<>();
    long total = 0L;
    for (ConsoleDashboardQueryRepository.StatusCountView row :
        repository.jobStatusCounts(resolved)) {
      long count = row.getCount() == null ? 0L : row.getCount();
      byStatus.put(row.getStatus(), count);
      total += count;
    }
    result.put("byStatus", byStatus);
    result.put("total", total);
    result.put(
        KEY_DAILY_TREND,
        repository.jobDailyTrend(resolved, days).stream()
            .map(
                row ->
                    Map.of(
                        KEY_DAY,
                        row.getDay(),
                        "status",
                        row.getStatus(),
                        KEY_COUNT,
                        row.getCount() == null ? 0L : row.getCount()))
            .toList());
    return result;
  }

  public Map<String, Object> triggerStats(String tenantId, int days) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put(
        "byTriggerType",
        repository.triggerTypeCounts(resolved).stream()
            .map(
                row ->
                    Map.of(
                        "type",
                        row.getType(),
                        KEY_COUNT,
                        row.getCount() == null ? 0L : row.getCount()))
            .toList());
    result.put(
        KEY_DAILY_TREND,
        repository.triggerDailyTrend(resolved, days).stream()
            .map(
                row ->
                    Map.of(
                        KEY_DAY,
                        row.getDay(),
                        KEY_COUNT,
                        row.getCount() == null ? 0L : row.getCount()))
            .toList());
    return result;
  }

  public Map<String, Object> workerLoad(String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put(
        "byStatus",
        repository.workerStatusCounts(resolved).stream()
            .map(
                row ->
                    Map.of(
                        "status",
                        row.getStatus(),
                        KEY_COUNT,
                        row.getCount() == null ? 0L : row.getCount()))
            .toList());
    result.put(
        "byWorkerGroup",
        repository.workerGroupStatusCounts(resolved).stream()
            .map(
                row ->
                    Map.of(
                        "workerGroup",
                        row.getWorkerGroup(),
                        "status",
                        row.getStatus(),
                        KEY_COUNT,
                        row.getCount() == null ? 0L : row.getCount()))
            .toList());
    result.put(
        "activePartitionsByWorker",
        repository.activePartitionsByWorker(resolved).stream()
            .map(
                row ->
                    Map.of(
                        "workerCode",
                        row.getWorkerCode(),
                        "activePartitions",
                        row.getActivePartitions() == null ? 0L : row.getActivePartitions()))
            .toList());
    return result;
  }

  public Map<String, Object> alertTrend(String tenantId, int days) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put(
        "bySeverity",
        repository.alertSeverityCounts(resolved).stream()
            .map(
                row ->
                    Map.of(
                        "severity",
                        row.getSeverity(),
                        KEY_COUNT,
                        row.getCount() == null ? 0L : row.getCount()))
            .toList());
    result.put(
        KEY_DAILY_TREND,
        repository.alertDailyTrend(resolved, days).stream()
            .map(
                row ->
                    Map.of(
                        KEY_DAY,
                        row.getDay(),
                        "severity",
                        row.getSeverity(),
                        KEY_COUNT,
                        row.getCount() == null ? 0L : row.getCount()))
            .toList());
    return result;
  }

  public List<Map<String, Object>> executionProgress(
      String tenantId, String jobCode, String bizDate) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return repository.executionProgress(resolved, jobCode, bizDate).stream()
        .map(
            row -> {
              Map<String, Object> item = new LinkedHashMap<>();
              item.put("id", row.getId());
              item.put("jobCode", row.getJobCode());
              item.put("instanceNo", row.getInstanceNo());
              item.put("instanceStatus", row.getInstanceStatus());
              int expected = row.getExpectedPartitions() == null ? 0 : row.getExpectedPartitions();
              int success = row.getSuccessPartitions() == null ? 0 : row.getSuccessPartitions();
              int failed = row.getFailedPartitions() == null ? 0 : row.getFailedPartitions();
              item.put("expectedPartitions", expected);
              item.put("successPartitions", success);
              item.put("failedPartitions", failed);
              item.put("completedPartitions", success + failed);
              item.put(
                  "progressPercent",
                  expected > 0 ? Math.round((success + failed) * 100.0 / expected) : 0);
              item.put("startedAt", row.getStartedAt());
              item.put("finishedAt", row.getFinishedAt());
              return item;
            })
        .toList();
  }

  public Map<String, Object> tenantUsage(String tenantId, int days) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("tenantId", resolved);
    result.put("jobDefinitions", nullToZero(repository.countJobDefinitions(resolved)));
    result.put("workflowDefinitions", nullToZero(repository.countWorkflowDefinitions(resolved)));
    result.put("fileChannels", nullToZero(repository.countFileChannels(resolved)));
    result.put("fileTemplates", nullToZero(repository.countFileTemplates(resolved)));
    result.put(
        "recentJobInstances", nullToZero(repository.countRecentJobInstances(resolved, days)));
    result.put("recentFiles", nullToZero(repository.countRecentFiles(resolved, days)));
    result.put("periodDays", days);
    return result;
  }

  private long nullToZero(Long value) {
    return value == null ? 0L : value;
  }

  public Map<String, Object> slaReport(String tenantId, int days) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("tenantId", resolved);
    result.put("periodDays", days);
    result.put(
        "jobs",
        repository.slaJobReport(resolved, days).stream()
            .map(
                row -> {
                  Map<String, Object> item = new LinkedHashMap<>();
                  item.put("jobCode", row.getJobCode());
                  item.put("jobName", row.getJobName());
                  item.put("totalInstances", nullToZero(row.getTotalInstances()));
                  item.put("successCount", nullToZero(row.getSuccessCount()));
                  item.put("failedCount", nullToZero(row.getFailedCount()));
                  item.put("slaBreached", nullToZero(row.getSlaBreached()));
                  item.put("slaOnTime", nullToZero(row.getSlaOnTime()));
                  item.put("avgDurationSeconds", row.getAvgDurationSeconds());
                  item.put("maxDurationSeconds", row.getMaxDurationSeconds());
                  item.put("totalPartitions", nullToZero(row.getTotalPartitions()));
                  return item;
                })
            .toList());
    return result;
  }

  public Map<String, Object> slaCompliance(String tenantId, int days) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    Map<String, Object> result = new LinkedHashMap<>();
    ConsoleDashboardQueryRepository.SlaStatsView stats = repository.slaStats(resolved, days);
    result.put("breached", stats == null || stats.breached() == null ? 0L : stats.breached());
    result.put("onTime", stats == null || stats.onTime() == null ? 0L : stats.onTime());
    result.put(
        "totalWithSla", stats == null || stats.totalWithSla() == null ? 0L : stats.totalWithSla());
    result.put("avgDurationSeconds", stats == null ? null : stats.avgDurationSeconds());
    result.put(
        KEY_DAILY_TREND,
        repository.slaDailyTrend(resolved, days).stream()
            .map(
                row ->
                    Map.of(
                        KEY_DAY,
                        row.getDay(),
                        "breached",
                        row.getBreached() == null ? 0L : row.getBreached(),
                        "onTime",
                        row.getOnTime() == null ? 0L : row.getOnTime()))
            .toList());
    return result;
  }
}
