package com.example.batch.orchestrator.infrastructure.scheduler;

import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.application.scheduler.ResourceQueueManager;
import com.example.batch.orchestrator.domain.entity.ResourceQueueRecord;
import com.example.batch.orchestrator.domain.scheduler.ResourceSchedulingRequest;
import com.example.batch.orchestrator.repository.ResourceQueueRepository;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 解析调度请求的目标资源队列：有显式 {@code queueCode} 时直查，否则在租户启用的所有队列里按下述优先级挑一个：
 *
 * <ol>
 *   <li>{@code queueType} 精确匹配 {@code workerType} 的队列优先于 {@code MIXED} 队列（避免"混合队列"抢走本该去专用队列的作业）。
 *   <li>{@code fairShareWeight} 倒序——权重大的队列更可能被选中。
 *   <li>{@code maxRunningJobs} 倒序——容量大的队列优先。
 *   <li>{@code maxRunningPartitions} 倒序——分区容量大的优先。
 *   <li>{@code queueCode} 字典序兜底，保证挑选结果稳定（防并发下随机性）。
 * </ol>
 *
 * <p>{@code fairShareWeight / maxRunningJobs / maxRunningPartitions} 的 null / ≤0 值统一规范化为 1。
 */
@Component
@RequiredArgsConstructor
public class DefaultResourceQueueManager implements ResourceQueueManager {

  private final ResourceQueueRepository resourceQueueRepository;

  @Override
  public ResourceQueueRecord resolveQueue(ResourceSchedulingRequest request) {
    if (request == null || !Texts.hasText(request.getTenantId())) {
      return null;
    }
    List<ResourceQueueRecord> queues =
        resourceQueueRepository.findByTenantIdAndEnabled(request.getTenantId(), true);
    if (queues == null || queues.isEmpty()) {
      return null;
    }
    if (Texts.hasText(request.getQueueCode())) {
      return queues.stream()
          .filter(queue -> request.getQueueCode().equalsIgnoreCase(queue.queueCode()))
          .findFirst()
          .orElse(null);
    }
    return queues.stream()
        .filter(queue -> matchesQueueType(queue, request.getWorkerType()))
        .sorted(
            Comparator.comparing(
                    (ResourceQueueRecord queue) -> !"MIXED".equalsIgnoreCase(queue.queueType()))
                .thenComparing(
                    queue -> normalizedWeight(queue.fairShareWeight()), Comparator.reverseOrder())
                .thenComparing(
                    queue -> normalizedWeight(queue.maxRunningJobs()), Comparator.reverseOrder())
                .thenComparing(
                    queue -> normalizedWeight(queue.maxRunningPartitions()),
                    Comparator.reverseOrder())
                .thenComparing(
                    ResourceQueueRecord::queueCode,
                    Comparator.nullsLast(String::compareToIgnoreCase)))
        .findFirst()
        .orElse(null);
  }

  private boolean matchesQueueType(ResourceQueueRecord queue, String workerType) {
    if (queue == null) {
      return false;
    }
    if (!Texts.hasText(workerType)) {
      return true;
    }
    return workerType.equalsIgnoreCase(queue.queueType())
        || "MIXED".equalsIgnoreCase(queue.queueType());
  }

  private Integer normalizedWeight(Integer weight) {
    return weight == null || weight <= 0 ? 1 : weight;
  }
}
