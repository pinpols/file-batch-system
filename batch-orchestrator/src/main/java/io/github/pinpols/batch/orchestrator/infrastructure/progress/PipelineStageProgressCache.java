package io.github.pinpols.batch.orchestrator.infrastructure.progress;

import io.github.pinpols.batch.common.dto.WorkerPipelineProgressDto;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
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
 * <p>内置 Worker 使用 task/pipeline/stage 精确键，支持一个实例并发多个 CLAIM 和多个分片；旧 SDK 仍可通过
 * workerCode 标量字段上报。缓存仅用于实时展示，持久化续跑仍由 {@code pipeline_progress} 表承担。
 */
@Component
public class PipelineStageProgressCache {

  private static final Duration TTL = Duration.ofMinutes(5);

  private final Clock clock;
  private final Map<TaskKey, Snapshot> taskStore = new ConcurrentHashMap<>();
  private final Map<TaskKey, WorkerKey> taskOwners = new ConcurrentHashMap<>();
  private final Map<WorkerKey, Set<TaskKey>> workerTasks = new ConcurrentHashMap<>();
  private final Map<WorkerKey, Snapshot> legacyStore = new ConcurrentHashMap<>();

  public PipelineStageProgressCache() {
    this(Clock.systemUTC());
  }

  PipelineStageProgressCache(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** 心跳路径调用；列表非 null 表示新版内置 Worker，空列表会清理该 worker 上一轮的全部任务。 */
  public void publish(
      String tenantId,
      String workerCode,
      List<WorkerPipelineProgressDto> pipelineProgress,
      Long legacyRowsProcessed,
      Long legacyTotalRowsHint) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(workerCode)) {
      return;
    }
    WorkerKey workerKey = new WorkerKey(tenantId, workerCode);
    if (EmptyChecks.isNull(pipelineProgress)) {
      publishLegacy(workerKey, legacyRowsProcessed, legacyTotalRowsHint);
      return;
    }

    legacyStore.remove(workerKey);
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

  /** 兼容旧 SDK/测试调用方的单槽上报。 */
  public void publish(String tenantId, String workerCode, Long rowsProcessed, Long totalRowsHint) {
    publish(tenantId, workerCode, null, rowsProcessed, totalRowsHint);
  }

  /**
   * 旧运维端点：按 workerCode 查询最新进度。
   *
   * <p>旧 SDK 直接返回标量槽位；新版内置 Worker 会聚合该节点当前持有的任务，避免任务级协议升级后旧运维端点突然返回空列表。
   * 该接口无法表达 stage 维度，只作为兼容观测入口，Console 主路径应使用 {@link #snapshotByPipeline}。
   */
  public Map<String, Snapshot> snapshot(String tenantId, Collection<String> workerCodes) {
    if (!Texts.hasText(tenantId) || EmptyChecks.isEmpty(workerCodes)) {
      return Map.of();
    }
    Instant cutoff = clock.instant().minus(TTL);
    return workerCodes.stream()
        .map(workerCode -> new WorkerKey(tenantId, workerCode))
        .map(key -> {
          Snapshot snapshot = activeSnapshot(legacyStore, key, cutoff);
          if (EmptyChecks.isNull(snapshot)) {
            snapshot = aggregateWorkerTasks(key, cutoff);
          }
          return EmptyChecks.isNull(snapshot) ? null : Map.entry(key.workerCode(), snapshot);
        })
        .filter(Objects::nonNull)
        .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  /** Console 主路径：按 pipeline 实例聚合同一 stage 下所有并发分片的实时进度。 */
  public List<PipelineSnapshot> snapshotByPipeline(String tenantId, Long pipelineInstanceId) {
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

  public void clearAllForTesting() {
    taskStore.clear();
    taskOwners.clear();
    workerTasks.clear();
    legacyStore.clear();
  }

  private void removeIfOwnedBy(TaskKey taskKey, WorkerKey workerKey) {
    if (taskOwners.remove(taskKey, workerKey)) {
      taskStore.remove(taskKey);
    }
  }

  private void publishLegacy(WorkerKey key, Long rowsProcessed, Long totalRowsHint) {
    if (EmptyChecks.isNull(rowsProcessed) && EmptyChecks.isNull(totalRowsHint)) {
      legacyStore.remove(key);
      return;
    }
    legacyStore.put(key, new Snapshot(rowsProcessed, totalRowsHint, clock.instant()));
  }

  private Snapshot aggregateWorkerTasks(WorkerKey workerKey, Instant cutoff) {
    Set<TaskKey> keys = workerTasks.get(workerKey);
    if (EmptyChecks.isEmpty(keys)) {
      return null;
    }
    List<Snapshot> snapshots = new ArrayList<>(keys.size());
    for (TaskKey key : keys) {
      Snapshot snapshot = activeTaskSnapshot(key, cutoff);
      if (EmptyChecks.isNull(snapshot)) {
        taskOwners.remove(key, workerKey);
      } else if (workerKey.equals(taskOwners.get(key))) {
        snapshots.add(snapshot);
      }
    }
    if (EmptyChecks.isEmpty(snapshots)) {
      workerTasks.remove(workerKey, keys);
      return null;
    }
    return aggregateSnapshots(snapshots);
  }

  private Snapshot activeTaskSnapshot(TaskKey key, Instant cutoff) {
    Snapshot snapshot = taskStore.get(key);
    if (EmptyChecks.isNull(snapshot)) {
      return null;
    }
    if (snapshot.heartbeatAt().isBefore(cutoff)) {
      removeExpiredTask(key, snapshot);
      return null;
    }
    return snapshot;
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

  private static <K> Snapshot activeSnapshot(Map<K, Snapshot> store, K key, Instant cutoff) {
    Snapshot snapshot = store.get(key);
    if (EmptyChecks.isNull(snapshot)) {
      return null;
    }
    if (snapshot.heartbeatAt().isBefore(cutoff)) {
      store.remove(key, snapshot);
      return null;
    }
    return snapshot;
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
