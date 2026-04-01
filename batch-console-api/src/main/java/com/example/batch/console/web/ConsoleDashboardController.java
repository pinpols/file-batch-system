package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.ConsoleTenantGuard;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/console/dashboard")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_CONFIG_ADMIN')")
@RequiredArgsConstructor
public class ConsoleDashboardController {

    private final ConsoleResponseFactory responseFactory;
    private final ConsoleTenantGuard tenantGuard;
    private final DataSource dataSource;

    @GetMapping("/job-stats")
    public CommonResponse<Map<String, Object>> jobStats(@RequestParam("tenantId") String tenantId,
            @RequestParam(value = "days", defaultValue = "7") int days) {
        String resolved = tenantGuard.resolveTenant(tenantId);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Map<String, Object> result = new LinkedHashMap<>();

        List<Map<String, Object>> statusCounts = jdbc.queryForList(
                "SELECT instance_status, count(1) as cnt FROM batch.job_instance WHERE tenant_id = ? GROUP BY instance_status",
                resolved);
        Map<String, Long> byStatus = new LinkedHashMap<>();
        long total = 0;
        for (Map<String, Object> row : statusCounts) {
            String status = (String) row.get("instance_status");
            long cnt = ((Number) row.get("cnt")).longValue();
            byStatus.put(status, cnt);
            total += cnt;
        }
        result.put("byStatus", byStatus);
        result.put("total", total);

        List<Map<String, Object>> dailyTrend = jdbc.queryForList(
                "SELECT cast(created_at as date) as day, instance_status, count(1) as cnt "
                        + "FROM batch.job_instance WHERE tenant_id = ? AND created_at >= current_date - ? "
                        + "GROUP BY day, instance_status ORDER BY day",
                resolved, days);
        result.put("dailyTrend", dailyTrend);

        return responseFactory.success(result);
    }

    @GetMapping("/trigger-stats")
    public CommonResponse<Map<String, Object>> triggerStats(@RequestParam("tenantId") String tenantId,
            @RequestParam(value = "days", defaultValue = "7") int days) {
        String resolved = tenantGuard.resolveTenant(tenantId);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Map<String, Object> result = new LinkedHashMap<>();

        List<Map<String, Object>> byType = jdbc.queryForList(
                "SELECT trigger_type, count(1) as cnt FROM batch.trigger_request WHERE tenant_id = ? GROUP BY trigger_type",
                resolved);
        result.put("byTriggerType", byType);

        List<Map<String, Object>> dailyTrend = jdbc.queryForList(
                "SELECT cast(created_at as date) as day, count(1) as cnt "
                        + "FROM batch.trigger_request WHERE tenant_id = ? AND created_at >= current_date - ? "
                        + "GROUP BY day ORDER BY day",
                resolved, days);
        result.put("dailyTrend", dailyTrend);

        return responseFactory.success(result);
    }

    @GetMapping("/worker-load")
    public CommonResponse<Map<String, Object>> workerLoad(@RequestParam("tenantId") String tenantId) {
        String resolved = tenantGuard.resolveTenant(tenantId);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Map<String, Object> result = new LinkedHashMap<>();

        List<Map<String, Object>> byStatus = jdbc.queryForList(
                "SELECT status, count(1) as cnt FROM batch.worker_registry WHERE tenant_id = ? GROUP BY status",
                resolved);
        result.put("byStatus", byStatus);

        List<Map<String, Object>> byWorkerGroup = jdbc.queryForList(
                "SELECT worker_group, status, count(1) as cnt FROM batch.worker_registry WHERE tenant_id = ? GROUP BY worker_group, status ORDER BY worker_group",
                resolved);
        result.put("byWorkerGroup", byWorkerGroup);

        List<Map<String, Object>> activePartitions = jdbc.queryForList(
                "SELECT p.worker_code, count(1) as active_partitions "
                        + "FROM batch.job_partition p WHERE p.tenant_id = ? AND p.partition_status IN ('READY', 'RUNNING') "
                        + "AND p.worker_code IS NOT NULL GROUP BY p.worker_code ORDER BY active_partitions DESC",
                resolved);
        result.put("activePartitionsByWorker", activePartitions);

        return responseFactory.success(result);
    }

    @GetMapping("/alert-trend")
    public CommonResponse<Map<String, Object>> alertTrend(@RequestParam("tenantId") String tenantId,
            @RequestParam(value = "days", defaultValue = "7") int days) {
        String resolved = tenantGuard.resolveTenant(tenantId);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Map<String, Object> result = new LinkedHashMap<>();

        List<Map<String, Object>> bySeverity = jdbc.queryForList(
                "SELECT severity, count(1) as cnt FROM batch.alert_event WHERE tenant_id = ? GROUP BY severity",
                resolved);
        result.put("bySeverity", bySeverity);

        List<Map<String, Object>> dailyTrend = jdbc.queryForList(
                "SELECT cast(created_at as date) as day, severity, count(1) as cnt "
                        + "FROM batch.alert_event WHERE tenant_id = ? AND created_at >= current_date - ? "
                        + "GROUP BY day, severity ORDER BY day",
                resolved, days);
        result.put("dailyTrend", dailyTrend);

        return responseFactory.success(result);
    }

    @GetMapping("/sla-compliance")
    public CommonResponse<Map<String, Object>> slaCompliance(@RequestParam("tenantId") String tenantId,
            @RequestParam(value = "days", defaultValue = "7") int days) {
        String resolved = tenantGuard.resolveTenant(tenantId);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Map<String, Object> result = new LinkedHashMap<>();

        Map<String, Object> slaStats = jdbc.queryForMap(
                "SELECT "
                        + "  count(1) FILTER (WHERE deadline_at IS NOT NULL AND finished_at > deadline_at) as breached, "
                        + "  count(1) FILTER (WHERE deadline_at IS NOT NULL AND (finished_at IS NULL OR finished_at <= deadline_at)) as on_time, "
                        + "  count(1) FILTER (WHERE deadline_at IS NOT NULL) as total_with_sla, "
                        + "  round(avg(EXTRACT(EPOCH FROM (finished_at - started_at)))::numeric, 2) as avg_duration_seconds "
                        + "FROM batch.job_instance "
                        + "WHERE tenant_id = ? AND created_at >= current_date - ? AND instance_status IN ('SUCCESS', 'FAILED', 'PARTIAL_FAILED')",
                resolved, days);
        result.putAll(slaStats);

        List<Map<String, Object>> dailySla = jdbc.queryForList(
                "SELECT cast(created_at as date) as day, "
                        + "  count(1) FILTER (WHERE deadline_at IS NOT NULL AND finished_at > deadline_at) as breached, "
                        + "  count(1) FILTER (WHERE deadline_at IS NOT NULL AND finished_at <= deadline_at) as on_time "
                        + "FROM batch.job_instance "
                        + "WHERE tenant_id = ? AND created_at >= current_date - ? AND instance_status IN ('SUCCESS', 'FAILED', 'PARTIAL_FAILED') "
                        + "GROUP BY day ORDER BY day",
                resolved, days);
        result.put("dailyTrend", dailySla);

        return responseFactory.success(result);
    }
}
