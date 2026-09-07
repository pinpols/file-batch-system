package io.github.pinpols.batch.orchestrator.application.plan;

import io.github.pinpols.batch.common.enums.ShardStrategy;
import io.github.pinpols.batch.orchestrator.config.PersistenceGranularityProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 根据预估数据量解析分区数。依次尝试：条目数 ÷ 每分区目标条目数、文件大小 ÷ 每分区目标字节数。 所需参数缺失或非正时返回 {@code 0}。 */
@Component
@Order(2)
public class SizeBasedPartitionCountResolver implements PartitionCountResolver {

  private final PersistenceGranularityProperties granularity;

  public SizeBasedPartitionCountResolver(PersistenceGranularityProperties granularity) {
    this.granularity = granularity;
  }

  @Override
  public int resolve(
      JobDefinitionEntity jobDefinition, Map<String, Object> params, ShardStrategy shardStrategy) {
    long estimatedItems = PartitionCountResolverSupport.firstPositiveLong(
        SizeBasedPartitionCountResolver.class,
        params.get("estimatedItemCount"),
        params.get("recordCount"),
        params.get("itemCount"),
        params.get("totalCount"));
    int targetItemsPerPartition = PartitionCountResolverSupport.firstPositiveInt(
        SizeBasedPartitionCountResolver.class,
        params.get("targetItemsPerPartition"),
        params.get("targetShardSize"),
        params.get("itemsPerPartition"));
    if (estimatedItems > 0 && targetItemsPerPartition > 0) {
      return ceilDiv(estimatedItems, targetItemsPerPartition);
    }

    if (estimatedItems > 0 && granularity.isEnabled()) {
      return resolveItemsByTier(estimatedItems);
    }

    long estimatedBytes = PartitionCountResolverSupport.firstPositiveLong(
        SizeBasedPartitionCountResolver.class,
        params.get("estimatedFileSizeBytes"),
        params.get("fileSizeBytes"),
        params.get("sourceFileSizeBytes"));
    long targetBytesPerPartition = PartitionCountResolverSupport.firstPositiveLong(
        SizeBasedPartitionCountResolver.class,
        params.get("targetBytesPerPartition"),
        params.get("targetShardBytes"));
    if (estimatedBytes > 0 && targetBytesPerPartition > 0) {
      return ceilDiv(estimatedBytes, targetBytesPerPartition);
    }
    if (estimatedBytes > 0 && granularity.isEnabled()) {
      return resolveBytesByTier(estimatedBytes);
    }
    return 0;
  }

  private int resolveItemsByTier(long estimatedItems) {
    if (estimatedItems <= positive(granularity.getCompactMaxItems())) {
      return 1;
    }
    long target = estimatedItems <= positive(granularity.getStandardMaxItems())
        ? positive(granularity.getStandardTargetItems())
        : positive(granularity.getLargeTargetItems());
    return ceilDiv(estimatedItems, target);
  }

  private int resolveBytesByTier(long estimatedBytes) {
    if (estimatedBytes <= positive(granularity.getCompactMaxBytes())) {
      return 1;
    }
    long target = estimatedBytes <= positive(granularity.getStandardMaxBytes())
        ? positive(granularity.getStandardTargetBytes())
        : positive(granularity.getLargeTargetBytes());
    return ceilDiv(estimatedBytes, target);
  }

  private long positive(long value) {
    return Math.max(1L, value);
  }

  private int ceilDiv(long dividend, long divisor) {
    if (dividend <= 0 || divisor <= 0) {
      return 1;
    }
    // 溢出安全:dividend 接近 Long.MAX_VALUE 时 (dividend + divisor - 1) 会溢出为负,
    // 导致分区数静默退化为 1(大文件被迫单分区串行)。改用 (dividend-1)/divisor + 1,并对
    // Integer.MAX_VALUE 封顶(后续 normalizePartitionCount 还会按 max 收敛)。
    long result = (dividend - 1) / divisor + 1;
    return (int) Math.min(result, Integer.MAX_VALUE);
  }
}
