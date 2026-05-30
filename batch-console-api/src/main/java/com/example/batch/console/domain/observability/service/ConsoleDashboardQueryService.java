package com.example.batch.console.domain.observability.service;

import com.example.batch.console.domain.observability.mapper.ConsoleDashboardQueryMapper;
import com.example.batch.console.domain.observability.view.dashboard.SlaStatsView;
import com.example.batch.console.domain.observability.view.dashboard.StatusCountView;
import com.example.batch.console.domain.rbac.support.ConsoleTenantGuard;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ConsoleDashboardQueryService {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String KEY_DAILY_TREND = "dailyTrend";
  private static final String KEY_DAY = "day";
  private static final String KEY_COUNT = "count";
  // null status / day 占位，13 处使用提常量
  private static final String UNKNOWN = "UNKNOWN";

  private final ConsoleDashboardQueryMapper repository;
  private final ConsoleTenantGuard tenantGuard;

  public ConsoleDashboardQueryService(
      ConsoleDashboardQueryMapper repository, ConsoleTenantGuard tenantGuard) {
    this.repository = repository;
    this.tenantGuard = tenantGuard;
  }

  public Map<String, Object> jobStats(String tenantId, int days) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    Map<String, Object> result = new LinkedHashMap<>();
    Map<String, Long> byStatus = new LinkedHashMap<>();
    long total = 0L;
    for (StatusCountView row : repository.jobStatusCounts(resolved)) {
      long count = row.count() == null ? 0L : row.count();
      byStatus.put(row.status() == null ? UNKNOWN : row.status(), count);
      total += count;
    }
    result.put("byStatus", byStatus);
    result.put("total", total);
    result.put(
        KEY_DAILY_TREND,
        repository.jobDailyTrend(resolved, days).stream()
            .map(
                row -> {
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put(KEY_DAY, row.day() == null ? UNKNOWN : row.day());
                  m.put("status", row.status() == null ? UNKNOWN : row.status());
                  m.put(KEY_COUNT, row.count() == null ? 0L : row.count());
                  return m;
                })
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
                row -> {
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("type", row.type() == null ? UNKNOWN : row.type());
                  m.put(KEY_COUNT, row.count() == null ? 0L : row.count());
                  return m;
                })
            .toList());
    result.put(
        KEY_DAILY_TREND,
        repository.triggerDailyTrend(resolved, days).stream()
            .map(
                row -> {
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put(KEY_DAY, row.day() == null ? UNKNOWN : row.day());
                  m.put(KEY_COUNT, row.count() == null ? 0L : row.count());
                  return m;
                })
            .toList());
    return result;
  }

  public Map<String, Object> workerLoad(String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    Map<String, Object> result = new LinkedHashMap<>();
    // 以下三个聚合查询的分组列（status / workerGroup / workerCode）DB 侧允许 null，
    // 但 Map.of(...) 禁止 null value —— 用 LinkedHashMap 容错 + UNKNOWN 占位。
    result.put(
        "byStatus",
        repository.workerStatusCounts(resolved).stream()
            .map(
                row -> {
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("status", row.status() == null ? UNKNOWN : row.status());
                  m.put(KEY_COUNT, row.count() == null ? 0L : row.count());
                  return m;
                })
            .toList());
    result.put(
        "byWorkerGroup",
        repository.workerGroupStatusCounts(resolved).stream()
            .map(
                row -> {
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("workerGroup", row.workerGroup() == null ? UNKNOWN : row.workerGroup());
                  m.put("status", row.status() == null ? UNKNOWN : row.status());
                  m.put(KEY_COUNT, row.count() == null ? 0L : row.count());
                  return m;
                })
            .toList());
    result.put(
        "activePartitionsByWorker",
        repository.activePartitionsByWorker(resolved).stream()
            .map(
                row -> {
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("workerCode", row.workerCode() == null ? UNKNOWN : row.workerCode());
                  m.put(
                      "activePartitions",
                      row.activePartitions() == null ? 0L : row.activePartitions());
                  return m;
                })
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
                row -> {
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("severity", row.severity() == null ? UNKNOWN : row.severity());
                  m.put(KEY_COUNT, row.count() == null ? 0L : row.count());
                  return m;
                })
            .toList());
    result.put(
        KEY_DAILY_TREND,
        repository.alertDailyTrend(resolved, days).stream()
            .map(
                row -> {
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put(KEY_DAY, row.day() == null ? UNKNOWN : row.day());
                  m.put("severity", row.severity() == null ? UNKNOWN : row.severity());
                  m.put(KEY_COUNT, row.count() == null ? 0L : row.count());
                  return m;
                })
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
              item.put("id", row.id());
              item.put("jobCode", row.jobCode());
              item.put("instanceNo", row.instanceNo());
              item.put("instanceStatus", row.instanceStatus());
              int expected = row.expectedPartitions() == null ? 0 : row.expectedPartitions();
              int success = row.successPartitions() == null ? 0 : row.successPartitions();
              int failed = row.failedPartitions() == null ? 0 : row.failedPartitions();
              item.put("expectedPartitions", expected);
              item.put("successPartitions", success);
              item.put("failedPartitions", failed);
              item.put("completedPartitions", success + failed);
              item.put(
                  "progressPercent",
                  expected > 0 ? Math.round((success + failed) * 100.0 / expected) : 0);
              item.put("startedAt", row.startedAt());
              item.put("finishedAt", row.finishedAt());
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
                  item.put("jobCode", row.jobCode());
                  item.put("jobName", row.jobName());
                  item.put("totalInstances", nullToZero(row.totalInstances()));
                  item.put("successCount", nullToZero(row.successCount()));
                  item.put("failedCount", nullToZero(row.failedCount()));
                  item.put("slaBreached", nullToZero(row.slaBreached()));
                  item.put("slaOnTime", nullToZero(row.slaOnTime()));
                  item.put("avgDurationSeconds", row.avgDurationSeconds());
                  item.put("maxDurationSeconds", row.maxDurationSeconds());
                  item.put("totalPartitions", nullToZero(row.totalPartitions()));
                  return item;
                })
            .toList());
    return result;
  }

  public Map<String, Object> slaCompliance(String tenantId, int days) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    Map<String, Object> result = new LinkedHashMap<>();
    SlaStatsView stats = repository.slaStats(resolved, days);
    result.put("breached", stats == null || stats.breached() == null ? 0L : stats.breached());
    result.put("onTime", stats == null || stats.onTime() == null ? 0L : stats.onTime());
    result.put(
        "totalWithSla", stats == null || stats.totalWithSla() == null ? 0L : stats.totalWithSla());
    result.put("avgDurationSeconds", stats == null ? null : stats.avgDurationSeconds());
    result.put(
        KEY_DAILY_TREND,
        repository.slaDailyTrend(resolved, days).stream()
            .map(
                row -> {
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put(KEY_DAY, row.day() == null ? UNKNOWN : row.day());
                  m.put("breached", row.breached() == null ? 0L : row.breached());
                  m.put("onTime", row.onTime() == null ? 0L : row.onTime());
                  return m;
                })
            .toList());
    return result;
  }
}
