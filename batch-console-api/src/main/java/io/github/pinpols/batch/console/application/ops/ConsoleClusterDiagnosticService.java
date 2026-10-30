package io.github.pinpols.batch.console.application.ops;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.OutboxPublishStatus;
import io.github.pinpols.batch.common.enums.WorkerRegistryStatus;
import io.github.pinpols.batch.common.utils.CodeNormalizer;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.console.domain.ops.application.contract.response.ConsoleClusterDiagnosticResponse;
import io.github.pinpols.batch.console.domain.ops.application.contract.response.ConsoleInstanceDiagnosisResponse;
import io.github.pinpols.batch.console.domain.ops.application.contract.response.ConsoleInstanceDiagnosisResponse.DiagnosisSummary;
import io.github.pinpols.batch.console.domain.ops.application.contract.response.ConsoleInstanceDiagnosisResponse.Finding;
import io.github.pinpols.batch.console.domain.ops.application.contract.response.ConsoleInstanceDiagnosisResponse.InstanceSummary;
import io.github.pinpols.batch.console.domain.ops.application.contract.response.ConsoleInstanceDiagnosisResponse.OutboxStatusCount;
import io.github.pinpols.batch.console.domain.ops.application.contract.response.ConsoleInstanceDiagnosisResponse.StatusCount;
import io.github.pinpols.batch.console.domain.ops.application.contract.response.ConsoleOutboxHealthResponse;
import io.github.pinpols.batch.console.domain.ops.application.contract.response.ConsoleShedLockStatusResponse;
import io.github.pinpols.batch.console.domain.ops.application.contract.response.ConsoleTerminalChildrenHealthResponse;
import io.github.pinpols.batch.console.domain.ops.application.contract.response.ConsoleWorkerConsistencyResponse;
import io.github.pinpols.batch.console.domain.ops.mapper.ConsoleClusterDiagnosticMapper;
import io.github.pinpols.batch.console.domain.ops.mapper.WorkerRegistryMapper;
import io.github.pinpols.batch.console.domain.ops.view.cluster.ActiveTaskWorkerIssueView;
import io.github.pinpols.batch.console.domain.ops.view.cluster.ClusterStatusCountView;
import io.github.pinpols.batch.console.domain.ops.view.cluster.JobInstanceDiagnosticView;
import io.github.pinpols.batch.console.domain.ops.view.cluster.OutboxStatusCountView;
import io.github.pinpols.batch.console.domain.ops.view.cluster.ShedLockView;
import io.github.pinpols.batch.console.domain.ops.view.cluster.WorkerGroupCapacityView;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import io.github.pinpols.batch.console.support.cache.ConsoleQueryCacheService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
  private static final Set<String> ACTIVE_INSTANCE_STATUSES = JobInstanceStatus.activeCodes();
  private static final Set<String> TERMINAL_INSTANCE_STATUSES = JobInstanceStatus.terminalCodes();
  private static final Set<String> ACTIVE_CHILD_STATUSES =
      Set.of("CREATED", "WAITING", "READY", "RUNNING", "RETRYING");
  private static final Set<String> ACTIVE_OUTBOX_STATUSES =
      Set.of("NEW", "PUBLISHING", "FAILED", "GIVE_UP");

  private final TenantIdResolver tenantGuard;
  private final ConsoleClusterDiagnosticMapper diagnosticMapper;
  private final WorkerRegistryMapper workerRegistryMapper;
  private final ConsoleQueryCacheService cacheService;

  public ConsoleClusterDiagnosticResponse diagnose(String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    String cacheKey = "diagnostic:" + cacheTenant(resolved) + ":all";
    return cacheService.getOrLoad(
        cacheKey,
        ConsoleQueryCacheService.DIAGNOSTIC_TTL,
        new TypeReference<ConsoleClusterDiagnosticResponse>() {},
        () -> loadDiagnose(resolved));
  }

  private ConsoleClusterDiagnosticResponse loadDiagnose(String resolved) {
    ConsoleShedLockStatusResponse shedLockStatus = loadShedLockStatus(resolved);
    ConsoleWorkerConsistencyResponse workerConsistency = loadWorkerConsistency(resolved);
    ConsoleOutboxHealthResponse outboxHealth = loadOutboxHealth(resolved);
    ConsoleTerminalChildrenHealthResponse terminalChildrenHealth =
        loadTerminalChildrenHealth(resolved);
    return new ConsoleClusterDiagnosticResponse(
        shedLockStatus, workerConsistency, outboxHealth, terminalChildrenHealth);
  }

  public ConsoleShedLockStatusResponse shedLockStatus(String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    String cacheKey = "diagnostic:" + cacheTenant(resolved) + ":shedlock";
    return cacheService.getOrLoad(
        cacheKey,
        ConsoleQueryCacheService.DIAGNOSTIC_TTL,
        new TypeReference<ConsoleShedLockStatusResponse>() {},
        () -> loadShedLockStatus(resolved));
  }

  private ConsoleShedLockStatusResponse loadShedLockStatus(String resolved) {
    List<ShedLockView> rows = diagnosticMapper.shedlockAll();
    long activeLocks =
        rows.stream().filter(row -> Boolean.TRUE.equals(row.active())).count();
    List<ConsoleShedLockStatusResponse.LockEntry> locks = rows.stream()
        .map(v -> new ConsoleShedLockStatusResponse.LockEntry(
            v.name(), v.lockUntil(), v.lockedAt(), v.lockedBy()))
        .toList();
    return new ConsoleShedLockStatusResponse(locks.size(), activeLocks, locks);
  }

  public ConsoleWorkerConsistencyResponse workerConsistency(String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    String cacheKey = "diagnostic:" + cacheTenant(resolved) + ":workers";
    return cacheService.getOrLoad(
        cacheKey,
        ConsoleQueryCacheService.DIAGNOSTIC_TTL,
        new TypeReference<ConsoleWorkerConsistencyResponse>() {},
        () -> loadWorkerConsistency(resolved));
  }

  private ConsoleWorkerConsistencyResponse loadWorkerConsistency(String resolved) {
    long online = workerRegistryMapper.countByStatus(resolved, WorkerRegistryStatus.ONLINE.code());
    long draining =
        workerRegistryMapper.countByStatus(resolved, WorkerRegistryStatus.DRAINING.code());
    long offline =
        workerRegistryMapper.countByStatus(resolved, WorkerRegistryStatus.OFFLINE.code());
    Long staleOnlineCount =
        diagnosticMapper.countStaleOnlineWorkers(resolved, WORKER_STALE_SECONDS);
    long staleOnline = valueOrZero(staleOnlineCount);
    Long drainingOverdueCount = diagnosticMapper.countDrainingPastDeadlineWorkers(resolved);
    long drainingOverdue = valueOrZero(drainingOverdueCount);
    Long decommissionedActiveCount =
        diagnosticMapper.countDecommissionedWorkersWithActiveTasks(resolved);
    long decommissionedActive = valueOrZero(decommissionedActiveCount);
    Long invalidCapabilityTagsCount = diagnosticMapper.countInvalidCapabilityTags(resolved);
    long invalidCapabilityTags = valueOrZero(invalidCapabilityTagsCount);
    List<String> runningStatuses = List.of(JobInstanceStatus.RUNNING.code());
    Long runningCount = diagnosticMapper.countJobInstancesByStatuses(resolved, runningStatuses);
    long running = valueOrZero(runningCount);
    List<ConsoleWorkerConsistencyResponse.WorkerGroupCapacity> workerGroups =
        diagnosticMapper.workerGroupCapacity(resolved).stream()
            .map(ConsoleClusterDiagnosticService::workerGroupCapacity)
            .toList();
    boolean healthy = (online > 0 || running == 0)
        && staleOnline == 0
        && drainingOverdue == 0
        && decommissionedActive == 0
        && invalidCapabilityTags == 0;
    return new ConsoleWorkerConsistencyResponse(
        online,
        draining,
        offline,
        staleOnline,
        drainingOverdue,
        decommissionedActive,
        invalidCapabilityTags,
        workerGroups,
        running,
        healthy);
  }

  public ConsoleOutboxHealthResponse outboxHealth(String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    String cacheKey = "diagnostic:" + cacheTenant(resolved) + ":outbox";
    return cacheService.getOrLoad(
        cacheKey,
        ConsoleQueryCacheService.DIAGNOSTIC_TTL,
        new TypeReference<ConsoleOutboxHealthResponse>() {},
        () -> loadOutboxHealth(resolved));
  }

  private ConsoleOutboxHealthResponse loadOutboxHealth(String resolved) {
    List<ConsoleOutboxHealthResponse.DeliveryStat> stats =
        diagnosticMapper.eventDeliveryStatusCounts(resolved).stream()
            .map(v -> new ConsoleOutboxHealthResponse.DeliveryStat(v.deliveryStatus(), v.cnt()))
            .toList();
    long pendingCount = diagnosticMapper.countPendingOutboxEvents(resolved);
    List<String> activeStatuses = List.of(
        OutboxPublishStatus.NEW.code(),
        OutboxPublishStatus.FAILED.code(),
        OutboxPublishStatus.PUBLISHING.code());
    Long activeEventCount = diagnosticMapper.countOutboxEventsByStatuses(resolved, activeStatuses);
    long activeCount = valueOrZero(activeEventCount);
    Long stalePublishingCount = diagnosticMapper.countStalePublishingOutboxEvents(
        resolved, OutboxPublishStatus.PUBLISHING.code(), OUTBOX_STALE_SECONDS);
    long stalePublishing = valueOrZero(stalePublishingCount);
    return new ConsoleOutboxHealthResponse(
        pendingCount,
        activeCount,
        stalePublishing,
        stats,
        pendingCount < 1000 && stalePublishing == 0);
  }

  public ConsoleTerminalChildrenHealthResponse terminalChildrenHealth(String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    String cacheKey = "diagnostic:" + cacheTenant(resolved) + ":terminal-children";
    return cacheService.getOrLoad(
        cacheKey,
        ConsoleQueryCacheService.DIAGNOSTIC_TTL,
        new TypeReference<ConsoleTerminalChildrenHealthResponse>() {},
        () -> loadTerminalChildrenHealth(resolved));
  }

  private ConsoleTerminalChildrenHealthResponse loadTerminalChildrenHealth(String resolved) {
    Long terminalInstancesWithActiveChildrenCount =
        diagnosticMapper.countTerminalInstancesWithActiveChildren(resolved);
    long terminalInstancesWithActiveChildren =
        valueOrZero(terminalInstancesWithActiveChildrenCount);
    return new ConsoleTerminalChildrenHealthResponse(
        terminalInstancesWithActiveChildren, terminalInstancesWithActiveChildren == 0);
  }

  public ConsoleInstanceDiagnosisResponse instanceDiagnosis(String tenantId, Long instanceId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    JobInstanceDiagnosticView instance = Guard.requireFound(
        diagnosticMapper.selectJobInstanceSummary(resolved, instanceId), "job instance not found");
    List<ClusterStatusCountView> partitionStatusCounts =
        diagnosticMapper.partitionStatusCounts(resolved, instanceId);
    List<ClusterStatusCountView> taskStatusCounts =
        diagnosticMapper.taskStatusCounts(resolved, instanceId);
    List<OutboxStatusCountView> outboxStatusCounts =
        diagnosticMapper.outboxStatusCountsForInstance(resolved, instanceId);
    List<ActiveTaskWorkerIssueView> workerIssues =
        diagnosticMapper.activeTaskWorkerIssues(resolved, instanceId, TASK_HEARTBEAT_STALE_SECONDS);
    String workerGroup = instance.workerGroup();
    String normalizedWorkerGroup = CodeNormalizer.toUpperOrNull(workerGroup);
    Long onlineWorkersForGroup = EmptyChecks.isNull(normalizedWorkerGroup)
        ? null
        : valueOrZero(diagnosticMapper.countOnlineWorkersForGroup(resolved, normalizedWorkerGroup));

    List<Finding> findings = new ArrayList<>();
    long totalPartitions = totalCount(partitionStatusCounts);
    long totalTasks = totalCount(taskStatusCounts);
    long activePartitions = countStatuses(partitionStatusCounts, ACTIVE_CHILD_STATUSES);
    long activeTasks = countStatuses(taskStatusCounts, ACTIVE_CHILD_STATUSES);
    long activeOutboxEvents = countOutboxStatuses(outboxStatusCounts, ACTIVE_OUTBOX_STATUSES);

    String currentStatus = instance.instanceStatus();
    String instanceStatus = EmptyChecks.isNull(currentStatus) ? "" : currentStatus;
    boolean activeInstanceWithoutChildren =
        isActive(instanceStatus) && totalPartitions == 0 && totalTasks == 0;
    if (activeInstanceWithoutChildren) {
      List<String> recommendations = List.of(
          "Check whether launch T1/T2 was interrupted",
          "Retry the instance or retrigger the same requestId");
      Map<String, Object> evidence = Map.of("instanceStatus", instanceStatus);
      findings.add(finding(
          "ERROR",
          "INSTANCE_HAS_NO_CHILDREN",
          "The instance is still active, but has no partition or task records.",
          recommendations,
          evidence));
    }
    boolean terminalInstanceHasActiveChildren =
        isTerminal(instanceStatus) && (activePartitions > 0 || activeTasks > 0);
    if (terminalInstanceHasActiveChildren) {
      List<String> recommendations = List.of(
          "Check orchestrator terminal-state progression logs first",
          "Use the controlled recovery endpoint for child nodes if needed");
      Map<String, Object> evidence =
          Map.of("activePartitions", activePartitions, "activeTasks", activeTasks);
      findings.add(finding(
          "ERROR",
          "TERMINAL_INSTANCE_HAS_ACTIVE_CHILDREN",
          "The instance is terminal, but active partitions or tasks still exist.",
          recommendations,
          evidence));
    }
    boolean activeInstanceWithoutOnlineWorker = isActive(instanceStatus)
        && EmptyChecks.isNotNull(onlineWorkersForGroup)
        && onlineWorkersForGroup == 0;
    if (activeInstanceWithoutOnlineWorker) {
      List<String> recommendations = List.of(
          "Start or recover a worker in that workerGroup",
          "Adjust job_definition.worker_group if needed");
      String instanceWorkerGroup = instance.workerGroup();
      String workerGroupEvidence =
          EmptyChecks.isNull(instanceWorkerGroup) ? "" : instanceWorkerGroup;
      Map<String, Object> evidence = Map.of("workerGroup", workerGroupEvidence);
      findings.add(finding(
          "WARN",
          "NO_ONLINE_WORKER_FOR_GROUP",
          "The instance's workerGroup has no ONLINE worker.",
          recommendations,
          evidence));
    }
    if (activeOutboxEvents > 0) {
      List<String> recommendations =
          List.of("Open the Outbox page", "Use controlled republish for FAILED/GIVE_UP events");
      Map<String, Object> evidence =
          Map.of("activeOutboxEvents", activeOutboxEvents, "statusCounts", outboxStatusCounts);
      findings.add(finding(
          "WARN",
          "OUTBOX_EVENTS_NOT_TERMINAL",
          "Not all outbox events for this instance have been published.",
          recommendations,
          evidence));
    }
    workerIssues.forEach(issue -> {
      String issueReason = issue.reasonCode();
      String reasonCode = EmptyChecks.isNull(issueReason) ? "TASK_WORKER_ISSUE" : issueReason;
      List<String> recommendations = List.of(
          "Check worker registration status and heartbeats",
          "Retry or cancel partitions after the lease is reclaimed");
      findings.add(finding(
          "WARN",
          reasonCode,
          "Active tasks have a worker assignment or heartbeat problem.",
          recommendations,
          workerIssueEvidence(issue)));
    });

    return new ConsoleInstanceDiagnosisResponse(
        resolved,
        instance.id(),
        EmptyChecks.isEmpty(findings),
        instanceSummary(instance),
        summary(partitionStatusCounts, taskStatusCounts, outboxStatusCounts, onlineWorkersForGroup),
        findings);
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

  private static long totalCount(Collection<ClusterStatusCountView> rows) {
    return rows.stream().mapToLong(row -> valueOrZero(row.count())).sum();
  }

  private static long countStatuses(
      Collection<? extends ClusterStatusCountView> rows, Set<String> statuses) {
    return rows.stream()
        .filter(row -> statuses.contains(row.status()))
        .mapToLong(row -> valueOrZero(row.count()))
        .sum();
  }

  private static long countOutboxStatuses(
      Collection<OutboxStatusCountView> rows, Set<String> statuses) {
    return rows.stream()
        .filter(row -> statuses.contains(row.status()))
        .mapToLong(row -> valueOrZero(row.count()))
        .sum();
  }

  private static ConsoleWorkerConsistencyResponse.WorkerGroupCapacity workerGroupCapacity(
      WorkerGroupCapacityView row) {
    return new ConsoleWorkerConsistencyResponse.WorkerGroupCapacity(
        row.workerGroup(),
        row.totalWorkers(),
        row.onlineWorkers(),
        row.drainingWorkers(),
        row.offlineWorkers(),
        row.decommissionedWorkers(),
        row.currentLoad(),
        row.maxConcurrent());
  }

  private static InstanceSummary instanceSummary(JobInstanceDiagnosticView instance) {
    return new InstanceSummary(
        instance.id(),
        instance.instanceNo(),
        instance.jobCode(),
        instance.bizDate(),
        instance.instanceStatus(),
        instance.queueCode(),
        instance.workerGroup(),
        instance.traceId(),
        instance.startedAt(),
        instance.deadlineAt(),
        Instant.now());
  }

  private static DiagnosisSummary summary(
      List<ClusterStatusCountView> partitionStatusCounts,
      List<ClusterStatusCountView> taskStatusCounts,
      List<OutboxStatusCountView> outboxStatusCounts,
      Long onlineWorkersForGroup) {
    List<StatusCount> partitionCounts = partitionStatusCounts.stream()
        .map(row -> new StatusCount(row.status(), row.count()))
        .toList();
    List<StatusCount> taskCounts = taskStatusCounts.stream()
        .map(row -> new StatusCount(row.status(), row.count()))
        .toList();
    List<OutboxStatusCount> outboxCounts = outboxStatusCounts.stream()
        .map(row -> new OutboxStatusCount(row.status(), row.count(), row.oldest(), row.newest()))
        .toList();
    return new DiagnosisSummary(partitionCounts, taskCounts, outboxCounts, onlineWorkersForGroup);
  }

  private static Map<String, Object> workerIssueEvidence(ActiveTaskWorkerIssueView issue) {
    Map<String, Object> evidence = new LinkedHashMap<>();
    putWhenPresent(evidence, "taskId", issue.taskId());
    putWhenPresent(evidence, "jobPartitionId", issue.jobPartitionId());
    putWhenPresent(evidence, "taskStatus", issue.taskStatus());
    putWhenPresent(evidence, "assignedWorkerCode", issue.assignedWorkerCode());
    putWhenPresent(evidence, "heartbeatAt", issue.heartbeatAt());
    putWhenPresent(evidence, "workerStatus", issue.workerStatus());
    putWhenPresent(evidence, "workerHeartbeatAt", issue.workerHeartbeatAt());
    putWhenPresent(evidence, "reasonCode", issue.reasonCode());
    return evidence;
  }

  private static void putWhenPresent(Map<String, Object> target, String key, Object value) {
    if (EmptyChecks.isNotNull(value)) {
      target.put(key, value);
    }
  }

  private static Finding finding(
      String severity,
      String reasonCode,
      String message,
      List<String> suggestedActions,
      Map<String, Object> evidence) {
    return new Finding(severity, reasonCode, message, suggestedActions, evidence);
  }
}
