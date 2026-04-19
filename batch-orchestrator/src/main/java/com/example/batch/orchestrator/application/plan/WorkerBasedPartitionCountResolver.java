package com.example.batch.orchestrator.application.plan;

import com.example.batch.common.enums.ShardStrategy;
import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.orchestrator.domain.entity.JobDefinitionRecord;
import com.example.batch.orchestrator.repository.WorkerRegistryRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import com.example.batch.common.utils.Texts;

/**
 * 根据当前在线 Worker 数量解析分区数，公式为 {@code min(256, onlineWorkerCount × partitionFactor)}。 DYNAMIC 策略下
 * partitionFactor 默认为 {@code 2}，其他策略为 {@code 1}；无在线 Worker 时返回 {@code 0}。
 */
@Component
@Order(4)
@RequiredArgsConstructor
public class WorkerBasedPartitionCountResolver implements PartitionCountResolver {

  private final WorkerRegistryRepository workerRegistryRepository;

  @Override
  public int resolve(
      JobDefinitionRecord jobDefinition, Map<String, Object> params, ShardStrategy shardStrategy) {
    long onlineWorkerCount = resolveOnlineWorkerCount(jobDefinition, params);
    if (onlineWorkerCount <= 0) {
      return 0;
    }
    int partitionFactor =
        firstPositiveInt(
            params.get("partitionFactor"),
            params.get("workerPartitionFactor"),
            shardStrategy == ShardStrategy.DYNAMIC ? 2 : 1);
    return (int) Math.min(256L, onlineWorkerCount * partitionFactor);
  }

  private long resolveOnlineWorkerCount(
      JobDefinitionRecord jobDefinition, Map<String, Object> params) {
    if (jobDefinition == null || !Texts.hasText(jobDefinition.tenantId())) {
      return firstPositiveLong(params.get("onlineWorkerCount"), params.get("workerCount"));
    }
    if (Texts.hasText(jobDefinition.workerGroup())) {
      return workerRegistryRepository.countByTenantIdAndWorkerGroupAndStatus(
          jobDefinition.tenantId(),
          jobDefinition.workerGroup(),
          WorkerRegistryStatus.ONLINE.code());
    }
    return workerRegistryRepository.countByTenantIdAndStatus(
        jobDefinition.tenantId(), WorkerRegistryStatus.ONLINE.code());
  }

  private int firstPositiveInt(Object... values) {
    for (Object value : values) {
      int candidate = toInt(value);
      if (candidate > 0) {
        return candidate;
      }
    }
    return 0;
  }

  private long firstPositiveLong(Object... values) {
    for (Object value : values) {
      long candidate = toLong(value);
      if (candidate > 0) {
        return candidate;
      }
    }
    return 0L;
  }

  private int toInt(Object value) {
    if (value == null) {
      return 0;
    }
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      return Integer.parseInt(String.valueOf(value).trim());
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }

  private long toLong(Object value) {
    if (value == null) {
      return 0L;
    }
    if (value instanceof Number number) {
      return number.longValue();
    }
    try {
      return Long.parseLong(String.valueOf(value).trim());
    } catch (NumberFormatException ignored) {
      return 0L;
    }
  }
}
