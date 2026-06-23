package io.github.pinpols.batch.orchestrator.application.plan;

import io.github.pinpols.batch.common.enums.ShardStrategy;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 从调用方显式传入的参数中解析分区数，优先级最高。 识别的参数键（按优先级）：{@code partitionCount}、{@code estimatedPartitionCount}、
 * {@code suggestedPartitionCount}、{@code shardCount}。
 */
@Component
@Order(1)
public class ExplicitPartitionCountResolver implements PartitionCountResolver {

  @Override
  public int resolve(
      JobDefinitionEntity jobDefinition, Map<String, Object> params, ShardStrategy shardStrategy) {
    return firstPositiveInt(
        params.get("partitionCount"),
        params.get("estimatedPartitionCount"),
        params.get("suggestedPartitionCount"),
        params.get("shardCount"));
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
      SwallowedExceptionLogger.info(
          ExplicitPartitionCountResolver.class, "catch:NumberFormatException", ignored);

      return 0;
    }
  }
}
