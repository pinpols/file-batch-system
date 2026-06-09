package com.example.batch.console.domain.ops.service;

import com.example.batch.common.enums.JobInstanceStatus;
import com.example.batch.common.enums.OutboxPublishStatus;
import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.console.domain.job.mapper.JobInstanceMapper;
import com.example.batch.console.domain.ops.mapper.ConsoleClusterDiagnosticMapper;
import com.example.batch.console.domain.ops.mapper.WorkerRegistryMapper;
import com.example.batch.console.domain.rbac.support.ConsoleTenantGuard;
import com.example.batch.console.support.cache.ConsoleQueryCacheService;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 跨节点一致性诊断服务：检查 ShedLock 租约、Worker 注册一致性、Outbox 健康。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConsoleClusterDiagnosticService {

  private static final long WORKER_STALE_SECONDS = 120L;
  private static final long OUTBOX_STALE_SECONDS = 120L;

  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleClusterDiagnosticMapper diagnosticMapper;
  private final WorkerRegistryMapper workerRegistryMapper;
  private final JobInstanceMapper jobInstanceMapper;
  private final ConsoleQueryCacheService cacheService;

  public Map<String, Object> diagnose(String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return cacheService.getOrLoad(
        "diagnostic:" + cacheTenant(resolved) + ":all",
        ConsoleQueryCacheService.DIAGNOSTIC_TTL,
        new TypeReference<Map<String, Object>>() {},
        () -> loadDiagnose(resolved));
  }

  private Map<String, Object> loadDiagnose(String resolved) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("shedLock", loadShedLockStatus(resolved));
    result.put("workers", loadWorkerConsistency(resolved));
    result.put("outbox", loadOutboxHealth(resolved));
    result.put("terminalChildren", loadTerminalChildrenHealth(resolved));
    return result;
  }

  public Map<String, Object> shedLockStatus(String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return cacheService.getOrLoad(
        "diagnostic:" + cacheTenant(resolved) + ":shedlock",
        ConsoleQueryCacheService.DIAGNOSTIC_TTL,
        new TypeReference<Map<String, Object>>() {},
        () -> loadShedLockStatus(resolved));
  }

  private Map<String, Object> loadShedLockStatus(String resolved) {
    List<Map<String, Object>> locks =
        diagnosticMapper.shedlockAll().stream()
            .map(
                v -> {
                  Map<String, Object> row = new LinkedHashMap<>();
                  row.put("name", v.getName());
                  row.put("lockUntil", v.getLockUntil());
                  row.put("lockedAt", v.getLockedAt());
                  row.put("lockedBy", v.getLockedBy());
                  return row;
                })
            .collect(Collectors.toList());
    long activeLocks = locks.stream().filter(row -> row.get("lockUntil") != null).count();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("totalLocks", locks.size());
    result.put("activeLocks", activeLocks);
    result.put("locks", locks);
    return result;
  }

  public Map<String, Object> workerConsistency(String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return cacheService.getOrLoad(
        "diagnostic:" + cacheTenant(resolved) + ":workers",
        ConsoleQueryCacheService.DIAGNOSTIC_TTL,
        new TypeReference<Map<String, Object>>() {},
        () -> loadWorkerConsistency(resolved));
  }

  private Map<String, Object> loadWorkerConsistency(String resolved) {
    long online = workerRegistryMapper.countByStatus(resolved, WorkerRegistryStatus.ONLINE.code());
    long draining =
        workerRegistryMapper.countByStatus(resolved, WorkerRegistryStatus.DRAINING.code());
    long offline =
        workerRegistryMapper.countByStatus(resolved, WorkerRegistryStatus.OFFLINE.code());
    long staleOnline =
        valueOrZero(diagnosticMapper.countStaleOnlineWorkers(resolved, WORKER_STALE_SECONDS));
    long drainingOverdue = valueOrZero(diagnosticMapper.countDrainingPastDeadlineWorkers(resolved));
    long decommissionedActive =
        valueOrZero(diagnosticMapper.countDecommissionedWorkersWithActiveTasks(resolved));
    long invalidCapabilityTags = valueOrZero(diagnosticMapper.countInvalidCapabilityTags(resolved));
    long running =
        jobInstanceMapper.countByStatuses(resolved, List.of(JobInstanceStatus.RUNNING.code()));
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("onlineWorkers", online);
    result.put("drainingWorkers", draining);
    result.put("offlineWorkers", offline);
    result.put("staleOnlineWorkers", staleOnline);
    result.put("drainingPastDeadlineWorkers", drainingOverdue);
    result.put("decommissionedWorkersWithActiveTasks", decommissionedActive);
    result.put("invalidCapabilityTags", invalidCapabilityTags);
    result.put("workerGroups", diagnosticMapper.workerGroupCapacity(resolved));
    result.put("runningInstances", running);
    result.put(
        "healthy",
        (online > 0 || running == 0)
            && staleOnline == 0
            && drainingOverdue == 0
            && decommissionedActive == 0
            && invalidCapabilityTags == 0);
    return result;
  }

  public Map<String, Object> outboxHealth(String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return cacheService.getOrLoad(
        "diagnostic:" + cacheTenant(resolved) + ":outbox",
        ConsoleQueryCacheService.DIAGNOSTIC_TTL,
        new TypeReference<Map<String, Object>>() {},
        () -> loadOutboxHealth(resolved));
  }

  private Map<String, Object> loadOutboxHealth(String resolved) {
    List<Map<String, Object>> stats =
        diagnosticMapper.eventDeliveryStatusCounts(resolved).stream()
            .map(
                v -> {
                  Map<String, Object> row = new LinkedHashMap<>();
                  row.put("deliveryStatus", v.getDeliveryStatus());
                  row.put("cnt", v.getCnt());
                  return row;
                })
            .collect(Collectors.toList());
    long pendingCount = diagnosticMapper.countPendingOutboxEvents(resolved);
    long activeCount =
        valueOrZero(
            diagnosticMapper.countOutboxEventsByStatuses(
                resolved,
                List.of(
                    OutboxPublishStatus.NEW.code(),
                    OutboxPublishStatus.FAILED.code(),
                    OutboxPublishStatus.PUBLISHING.code())));
    long stalePublishing =
        valueOrZero(
            diagnosticMapper.countStalePublishingOutboxEvents(
                resolved, OutboxPublishStatus.PUBLISHING.code(), OUTBOX_STALE_SECONDS));
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("pendingEvents", pendingCount);
    result.put("activeEvents", activeCount);
    result.put("stalePublishingEvents", stalePublishing);
    result.put("deliveryStats", stats);
    result.put("healthy", pendingCount < 1000 && stalePublishing == 0);
    return result;
  }

  public Map<String, Object> terminalChildrenHealth(String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return cacheService.getOrLoad(
        "diagnostic:" + cacheTenant(resolved) + ":terminal-children",
        ConsoleQueryCacheService.DIAGNOSTIC_TTL,
        new TypeReference<Map<String, Object>>() {},
        () -> loadTerminalChildrenHealth(resolved));
  }

  private Map<String, Object> loadTerminalChildrenHealth(String resolved) {
    long terminalInstancesWithActiveChildren =
        valueOrZero(diagnosticMapper.countTerminalInstancesWithActiveChildren(resolved));
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("terminalInstancesWithActiveChildren", terminalInstancesWithActiveChildren);
    result.put("healthy", terminalInstancesWithActiveChildren == 0);
    return result;
  }

  private static long valueOrZero(Long value) {
    return value == null ? 0L : value;
  }

  private static String cacheTenant(String tenantId) {
    return ConsoleQueryCacheService.keySegment(tenantId);
  }
}
