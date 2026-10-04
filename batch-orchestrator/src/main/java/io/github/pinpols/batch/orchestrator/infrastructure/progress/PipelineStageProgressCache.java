package io.github.pinpols.batch.orchestrator.infrastructure.progress;

import io.github.pinpols.batch.common.dto.WorkerPipelineProgressDto;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Pipeline stage 行级进度的 orchestrator 节点本地缓存。
 *
 * <p>使用 task/pipeline/stage 精确键隔离并发任务；不再接收进程级标量进度。
 * 缓存仅用于实时展示，持久化续跑仍由 {@code pipeline_progress} 表承担。
 */
@Component
public class PipelineStageProgressCache {

  private static final Duration TTL = Duration.ofMinutes(5);

  private final Clock clock;
  private final Map<TaskKey, Snapshot> taskStore = new ConcurrentHashMap<>();
  private final Map<TaskKey, WorkerKey> taskOwners = new ConcurrentHashMap<>();
  private final Map<WorkerKey, Set<TaskKey>> workerTasks = new ConcurrentHashMap<>();

  public PipelineStageProgressCache() {
    this(Clock.systemUTC());
  }

  PipelineStageProgressCache(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** 心跳全量快照；未提供进度或空列表会清理该 worker 上一轮的全部任务。 */
  public synchronized void publish(
      String tenantId, String workerCode, List<WorkerPipelineProgressDto> pipelineProgress) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(workerCode)) {
      return;
    }
    WorkerKey workerKey = new WorkerKey(tenantId, workerCode);
    if (EmptyChecks.isNull(pipelineProgress)) {
      pipelineProgress = List.of();
    }

    Instant heartbeatAt = clock.instant();
    Set<TaskKey> currentKeys = pipelineProgress.stream()
        .filter(Objects::nonNull)
        .filter(PipelineStageProgressCache::isValid)
        .map(item -> {
          TaskKey key =
              new TaskKey(tenantId, item.pipelineInstanceId(), item.taskId(), item.stageCode());
          taskStore.put(key, new Snapshot(item.rowsProcessed(), item.totalRowsHint(), heartbeatAt));
          taskOwners.put(key, workerKey);
          return key;
        })
        .collect(Collectors.toUnmodifiableSet());
    Set<TaskKey> previousKeys = workerTasks.put(workerKey, currentKeys);
    if (EmptyChecks.isNotNull(previousKeys)) {
      previousKeys.stream()
          .filter(previous -> !currentKeys.contains(previous))
          .forEach(previous -> removeIfOwnedBy(previous, workerKey));
    }
  }

  /** Console 主路径：按 pipeline 实例聚合同一 stage 下所有并发分片的实时进度。 */
  public synchronized List<PipelineSnapshot> snapshotByPipeline(
      String tenantId, Long pipelineInstanceId) {
    if (!Texts.hasText(tenantId) || EmptyChecks.isNull(pipelineInstanceId)) {
      return List.of();
    }
    Instant cutoff = clock.instant().minus(TTL);
    Map<String, List<Snapshot>> grouped = taskStore.entrySet().stream()
        .filter(entry -> tenantId.equals(entry.getKey().tenantId()))
        .filter(entry -> pipelineInstanceId.equals(entry.getKey().pipelineInstanceId()))
        .filter(entry -> {
          if (entry.getValue().heartbeatAt().isBefore(cutoff)) {
            removeExpiredTask(entry.getKey(), entry.getValue());
            return false;
          }
          return true;
        })
        .collect(Collectors.groupingBy(
            entry -> entry.getKey().stageCode(),
            Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    return grouped.entrySet().stream()
        .map(entry -> aggregate(entry.getKey(), entry.getValue()))
        .sorted(Comparator.comparing(PipelineSnapshot::stageCode))
        .toList();
  }

  public synchronized void clearAllForTesting() {
    taskStore.clear();
    taskOwners.clear();
    workerTasks.clear();
  }

  private void removeIfOwnedBy(TaskKey taskKey, WorkerKey workerKey) {
    if (taskOwners.remove(taskKey, workerKey)) {
      taskStore.remove(taskKey);
    }
  }

  private void removeExpiredTask(TaskKey taskKey, Snapshot snapshot) {
    if (!taskStore.remove(taskKey, snapshot)) {
      return;
    }
    WorkerKey owner = taskOwners.remove(taskKey);
    if (EmptyChecks.isNull(owner)) {
      return;
    }
    Set<TaskKey> indexedTasks = workerTasks.get(owner);
    if (EmptyChecks.isNotNull(indexedTasks)
        && indexedTasks.stream().noneMatch(taskStore::containsKey)) {
      workerTasks.remove(owner, indexedTasks);
    }
  }

  private static boolean isValid(WorkerPipelineProgressDto item) {
    return EmptyChecks.isNotNull(item.taskId())
        && EmptyChecks.isNotNull(item.pipelineInstanceId())
        && Texts.hasText(item.stageCode())
        && EmptyChecks.isNotNull(item.rowsProcessed());
  }

  private static PipelineSnapshot aggregate(String stageCode, List<Snapshot> snapshots) {
    Snapshot aggregate = aggregateSnapshots(snapshots);
    return new PipelineSnapshot(
        stageCode, aggregate.rowsProcessed(), aggregate.totalRowsHint(), aggregate.heartbeatAt());
  }

  private static Snapshot aggregateSnapshots(List<Snapshot> snapshots) {
    long rowsProcessed = snapshots.stream()
        .map(Snapshot::rowsProcessed)
        .filter(Objects::nonNull)
        .mapToLong(Long::longValue)
        .sum();
    boolean allTotalsKnown =
        snapshots.stream().allMatch(item -> EmptyChecks.isNotNull(item.totalRowsHint()));
    Long totalRowsHint =
        allTotalsKnown ? snapshots.stream().mapToLong(Snapshot::totalRowsHint).sum() : null;
    Instant heartbeatAt =
        snapshots.stream().map(Snapshot::heartbeatAt).max(Instant::compareTo).orElse(null);
    return new Snapshot(rowsProcessed, totalRowsHint, heartbeatAt);
  }

  private record WorkerKey(String tenantId, String workerCode) {}

  private record TaskKey(String tenantId, Long pipelineInstanceId, Long taskId, String stageCode) {}

  public record Snapshot(Long rowsProcessed, Long totalRowsHint, Instant heartbeatAt) {}

  public record PipelineSnapshot(
      String stageCode, Long rowsProcessed, Long totalRowsHint, Instant heartbeatAt) {}
}
