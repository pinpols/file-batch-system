package com.example.batch.console.service;

import com.example.batch.console.repository.ConsoleDashboardQueryRepository;
import com.example.batch.console.support.ConsoleTenantGuard;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ConsoleDashboardQueryService {

    private final ConsoleDashboardQueryRepository repository;
    private final ConsoleTenantGuard tenantGuard;

    public ConsoleDashboardQueryService(ConsoleDashboardQueryRepository repository, ConsoleTenantGuard tenantGuard) {
        this.repository = repository;
        this.tenantGuard = tenantGuard;
    }

    public Map<String, Object> jobStats(String tenantId, int days) {
        String resolved = tenantGuard.resolveTenant(tenantId);
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        long total = 0L;
        for (ConsoleDashboardQueryRepository.StatusCountView row : repository.jobStatusCounts(resolved)) {
            long count = row.getCount() == null ? 0L : row.getCount();
            byStatus.put(row.getStatus(), count);
            total += count;
        }
        result.put("byStatus", byStatus);
        result.put("total", total);
        result.put("dailyTrend", repository.jobDailyTrend(resolved, days).stream().map(row -> Map.of(
                "day", row.getDay(),
                "status", row.getStatus(),
                "count", row.getCount() == null ? 0L : row.getCount()
        )).toList());
        return result;
    }

    public Map<String, Object> triggerStats(String tenantId, int days) {
        String resolved = tenantGuard.resolveTenant(tenantId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("byTriggerType", repository.triggerTypeCounts(resolved).stream().map(row -> Map.of(
                "type", row.getType(),
                "count", row.getCount() == null ? 0L : row.getCount()
        )).toList());
        result.put("dailyTrend", repository.triggerDailyTrend(resolved, days).stream().map(row -> Map.of(
                "day", row.getDay(),
                "count", row.getCount() == null ? 0L : row.getCount()
        )).toList());
        return result;
    }

    public Map<String, Object> workerLoad(String tenantId) {
        String resolved = tenantGuard.resolveTenant(tenantId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("byStatus", repository.workerStatusCounts(resolved).stream().map(row -> Map.of(
                "status", row.getStatus(),
                "count", row.getCount() == null ? 0L : row.getCount()
        )).toList());
        result.put("byWorkerGroup", repository.workerGroupStatusCounts(resolved).stream().map(row -> Map.of(
                "workerGroup", row.getWorkerGroup(),
                "status", row.getStatus(),
                "count", row.getCount() == null ? 0L : row.getCount()
        )).toList());
        result.put("activePartitionsByWorker", repository.activePartitionsByWorker(resolved).stream().map(row -> Map.of(
                "workerCode", row.getWorkerCode(),
                "activePartitions", row.getActivePartitions() == null ? 0L : row.getActivePartitions()
        )).toList());
        return result;
    }

    public Map<String, Object> alertTrend(String tenantId, int days) {
        String resolved = tenantGuard.resolveTenant(tenantId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bySeverity", repository.alertSeverityCounts(resolved).stream().map(row -> Map.of(
                "severity", row.getSeverity(),
                "count", row.getCount() == null ? 0L : row.getCount()
        )).toList());
        result.put("dailyTrend", repository.alertDailyTrend(resolved, days).stream().map(row -> Map.of(
                "day", row.getDay(),
                "severity", row.getSeverity(),
                "count", row.getCount() == null ? 0L : row.getCount()
        )).toList());
        return result;
    }

    public Map<String, Object> slaCompliance(String tenantId, int days) {
        String resolved = tenantGuard.resolveTenant(tenantId);
        Map<String, Object> result = new LinkedHashMap<>();
        ConsoleDashboardQueryRepository.SlaStatsView stats = repository.slaStats(resolved, days);
        result.put("breached", stats == null || stats.getBreached() == null ? 0L : stats.getBreached());
        result.put("onTime", stats == null || stats.getOnTime() == null ? 0L : stats.getOnTime());
        result.put("totalWithSla", stats == null || stats.getTotalWithSla() == null ? 0L : stats.getTotalWithSla());
        result.put("avgDurationSeconds", stats == null ? null : stats.getAvgDurationSeconds());
        result.put("dailyTrend", repository.slaDailyTrend(resolved, days).stream().map(row -> Map.of(
                "day", row.getDay(),
                "breached", row.getBreached() == null ? 0L : row.getBreached(),
                "onTime", row.getOnTime() == null ? 0L : row.getOnTime()
        )).toList());
        return result;
    }
}
