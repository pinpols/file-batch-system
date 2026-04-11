package com.example.batch.console.service;

import com.example.batch.common.enums.JobInstanceStatus;
import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.console.mapper.JobInstanceMapper;
import com.example.batch.console.mapper.WorkerRegistryMapper;
import com.example.batch.console.support.ConsoleTenantGuard;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 跨节点一致性诊断服务：检查 ShedLock 租约、Worker 注册一致性、Outbox 健康。 */
@Service
@RequiredArgsConstructor
public class ConsoleClusterDiagnosticService {

    private final ConsoleTenantGuard tenantGuard;
    private final JdbcTemplate jdbcTemplate;
    private final WorkerRegistryMapper workerRegistryMapper;
    private final JobInstanceMapper jobInstanceMapper;

    public Map<String, Object> diagnose(String tenantId) {
        String resolved = tenantGuard.resolveTenant(tenantId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shedLock", shedLockStatus(resolved));
        result.put("workers", workerConsistency(resolved));
        result.put("outbox", outboxHealth(resolved));
        return result;
    }

    public Map<String, Object> shedLockStatus(String tenantId) {
        tenantGuard.resolveTenant(tenantId);
        List<Map<String, Object>> locks =
                jdbcTemplate.queryForList(
                        "SELECT name, lock_until, locked_at, locked_by FROM batch.shedlock ORDER BY"
                            + " name");
        long activeLocks = locks.stream().filter(row -> row.get("lock_until") != null).count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalLocks", locks.size());
        result.put("activeLocks", activeLocks);
        result.put("locks", locks);
        return result;
    }

    public Map<String, Object> workerConsistency(String tenantId) {
        String resolved = tenantGuard.resolveTenant(tenantId);
        long online =
                workerRegistryMapper.countByStatus(resolved, WorkerRegistryStatus.ONLINE.code());
        long draining =
                workerRegistryMapper.countByStatus(resolved, WorkerRegistryStatus.DRAINING.code());
        long offline =
                workerRegistryMapper.countByStatus(resolved, WorkerRegistryStatus.OFFLINE.code());
        long running =
                jobInstanceMapper.countByStatuses(
                        resolved, List.of(JobInstanceStatus.RUNNING.code()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("onlineWorkers", online);
        result.put("drainingWorkers", draining);
        result.put("offlineWorkers", offline);
        result.put("runningInstances", running);
        result.put("healthy", online > 0 || running == 0);
        return result;
    }

    public Map<String, Object> outboxHealth(String tenantId) {
        String resolved = tenantGuard.resolveTenant(tenantId);
        List<Map<String, Object>> stats =
                jdbcTemplate.queryForList(
                        """
                        SELECT delivery_status, count(*) as cnt
                          FROM batch.outbox_delivery_log
                         WHERE tenant_id = ?
                         GROUP BY delivery_status
                        """,
                        resolved);
        long pendingCount =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM batch.outbox_event WHERE tenant_id = ? AND status ="
                            + " 'PENDING'",
                        Long.class,
                        resolved);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pendingEvents", pendingCount);
        result.put("deliveryStats", stats);
        result.put("healthy", pendingCount < 1000);
        return result;
    }
}
