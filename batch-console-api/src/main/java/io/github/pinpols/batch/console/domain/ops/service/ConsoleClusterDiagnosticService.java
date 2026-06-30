package io.github.pinpols.batch.console.domain.ops.service;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.OutboxPublishStatus;
import io.github.pinpols.batch.common.enums.WorkerRegistryStatus;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.console.domain.ops.mapper.ConsoleClusterDiagnosticMapper;
import io.github.pinpols.batch.console.domain.ops.mapper.WorkerRegistryMapper;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.support.cache.ConsoleQueryCacheService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
  private static final long TASK_HEARTBEAT_STALE_SECONDS = 120L;
  private static final Set<String> ACTIVE_INSTANCE_STATUSES =
      Set.of("CREATED", "WAITING", "READY", "RUNNING", "PAUSED", "PARTIAL_FAILED");
  private static final Set<String> TERMINAL_INSTANCE_STATUSES =
      Set.of("SUCCESS", "FAILED", "CANCELLED", "TERMINATED", "SUCCESS_DRY_RUN", "FAILED_DRY_RUN");
  private static final Set<String> ACTIVE_CHILD_STATUSES =
      Set.of("CREATED", "WAITING", "READY", "RUNNING", "RETRYING");
  private static final Set<String> ACTIVE_OUTBOX_STATUSES =
      Set.of("NEW", "PUBLISHING", "FAILED", "GIVE_UP");

  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleClusterDiagnosticMapper diagnosticMapper;
  private final WorkerRegistryMapper workerRegistryMapper;
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
        valueOrZero(
            diagnosticMapper.countJobInstancesByStatuses(
                resolved, List.of(JobInstanceStatus.RUNNING.code())));
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

  public Map<String, Object> instanceDiagnosis(String tenantId, Long instanceId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    Map<String, Object> instance =
        Guard.requireFound(
            diagnosticMapper.selectJobInstanceSummary(resolved, instanceId),
            "job instance not found");
    List<Map<String, Object>> partitionStatusCounts =
        diagnosticMapper.partitionStatusCounts(resolved, instanceId);
    List<Map<String, Object>> taskStatusCounts =
        diagnosticMapper.taskStatusCounts(resolved, instanceId);
    List<Map<String, Object>> outboxStatusCounts =
        diagnosticMapper.outboxStatusCountsForInstance(resolved, instanceId);
    List<Map<String, Object>> workerIssues =
        diagnosticMapper.activeTaskWorkerIssues(resolved, instanceId, TASK_HEARTBEAT_STALE_SECONDS);
    long onlineWorkersForGroup =
        valueOrZero(
            diagnosticMapper.countOnlineWorkersForGroup(
                resolved, stringValue(instance.get("workerGroup"), null)));

    List<Map<String, Object>> findings = new ArrayList<>();
    long totalPartitions = totalCount(partitionStatusCounts);
    long totalTasks = totalCount(taskStatusCounts);
    long activePartitions = countStatuses(partitionStatusCounts, ACTIVE_CHILD_STATUSES);
    long activeTasks = countStatuses(taskStatusCounts, ACTIVE_CHILD_STATUSES);
    long activeOutboxEvents = countStatuses(outboxStatusCounts, ACTIVE_OUTBOX_STATUSES);

    String instanceStatus = stringValue(instance.get("instanceStatus"), "");
    if (isActive(instanceStatus) && totalPartitions == 0 && totalTasks == 0) {
      findings.add(
          finding(
              "ERROR",
              "INSTANCE_HAS_NO_CHILDREN",
              "实例仍处于活跃状态,但没有分区和任务记录。",
              List.of("检查 launch T1/T2 是否中断", "使用实例重试或重新触发同一 requestId"),
              Map.of("instanceStatus", instanceStatus)));
    }
    if (isTerminal(instanceStatus) && (activePartitions > 0 || activeTasks > 0)) {
      findings.add(
          finding(
              "ERROR",
              "TERMINAL_INSTANCE_HAS_ACTIVE_CHILDREN",
              "实例已终态,但仍存在活跃分区或任务。",
              List.of("优先检查 orchestrator 终态推进日志", "必要时通过受控恢复接口处理子节点"),
              Map.of("activePartitions", activePartitions, "activeTasks", activeTasks)));
    }
    if (isActive(instanceStatus) && onlineWorkersForGroup == 0) {
      findings.add(
          finding(
              "WARN",
              "NO_ONLINE_WORKER_FOR_GROUP",
              "实例所属 workerGroup 当前没有 ONLINE worker。",
              List.of("启动或恢复该 workerGroup 的 worker", "必要时调整 job_definition.worker_group"),
              Map.of("workerGroup", stringValue(instance.get("workerGroup"), ""))));
    }
    if (activeOutboxEvents > 0) {
      findings.add(
          finding(
              "WARN",
              "OUTBOX_EVENTS_NOT_TERMINAL",
              "该实例相关 outbox 事件仍未全部发布完成。",
              List.of("查看 Outbox 页面", "对 FAILED/GIVE_UP 事件使用受控 republish"),
              Map.of(
                  "activeOutboxEvents", activeOutboxEvents, "statusCounts", outboxStatusCounts)));
    }
    workerIssues.forEach(
        issue ->
            findings.add(
                finding(
                    "WARN",
                    stringValue(issue.get("reasonCode"), "TASK_WORKER_ISSUE"),
                    "活跃任务存在 worker 分配或心跳异常。",
                    List.of("查看 worker 注册状态与心跳", "等待 lease 回收后重试或取消分区"),
                    issue)));

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("tenantId", resolved);
    result.put("jobInstanceId", instance.get("id"));
    result.put("healthy", findings.isEmpty());
    result.put("instance", instanceSummary(instance));
    result.put(
        "summary",
        summary(
            partitionStatusCounts, taskStatusCounts, outboxStatusCounts, onlineWorkersForGroup));
    result.put("findings", findings);
    return result;
  }

  private static long valueOrZero(Long value) {
    return value == null ? 0L : value;
  }

  private static String cacheTenant(String tenantId) {
    return ConsoleQueryCacheService.keySegment(tenantId);
  }

  private static boolean isActive(String status) {
    return ACTIVE_INSTANCE_STATUSES.contains(status);
  }

  private static boolean isTerminal(String status) {
    return TERMINAL_INSTANCE_STATUSES.contains(status);
  }

  private static long totalCount(Collection<Map<String, Object>> rows) {
    return rows.stream().mapToLong(row -> longValue(row.get("count"))).sum();
  }

  private static long countStatuses(Collection<Map<String, Object>> rows, Set<String> statuses) {
    return rows.stream()
        .filter(row -> statuses.contains(stringValue(row.get("status"), "")))
        .mapToLong(row -> longValue(row.get("count")))
        .sum();
  }

  private static Map<String, Object> instanceSummary(Map<String, Object> instance) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", instance.get("id"));
    result.put("instanceNo", instance.get("instanceNo"));
    result.put("jobCode", instance.get("jobCode"));
    result.put("bizDate", instance.get("bizDate"));
    result.put("instanceStatus", instance.get("instanceStatus"));
    result.put("queueCode", instance.get("queueCode"));
    result.put("workerGroup", instance.get("workerGroup"));
    result.put("traceId", instance.get("traceId"));
    result.put("startedAt", instance.get("startedAt"));
    result.put("deadlineAt", instance.get("deadlineAt"));
    result.put("now", Instant.now());
    return result;
  }

  private static Map<String, Object> summary(
      List<Map<String, Object>> partitionStatusCounts,
      List<Map<String, Object>> taskStatusCounts,
      List<Map<String, Object>> outboxStatusCounts,
      long onlineWorkersForGroup) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("partitionStatusCounts", partitionStatusCounts);
    result.put("taskStatusCounts", taskStatusCounts);
    result.put("outboxStatusCounts", outboxStatusCounts);
    result.put("onlineWorkersForGroup", onlineWorkersForGroup);
    return result;
  }

  private static Map<String, Object> finding(
      String severity,
      String reasonCode,
      String message,
      List<String> suggestedActions,
      Map<String, Object> evidence) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("severity", severity);
    result.put("reasonCode", reasonCode);
    result.put("message", message);
    result.put("suggestedActions", suggestedActions);
    result.put("evidence", evidence);
    return result;
  }

  private static long longValue(Object value) {
    if (value instanceof Number n) {
      return n.longValue();
    }
    return value == null ? 0L : Long.parseLong(value.toString());
  }

  private static String stringValue(Object value, String fallback) {
    return value == null ? fallback : value.toString();
  }
}
