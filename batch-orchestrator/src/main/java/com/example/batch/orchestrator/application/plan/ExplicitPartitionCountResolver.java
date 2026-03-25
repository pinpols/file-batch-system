package com.example.batch.orchestrator.application.plan;

import com.example.batch.common.enums.ShardStrategy;
import com.example.batch.orchestrator.domain.entity.JobDefinitionRecord;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Resolves partition count from an explicit caller-supplied override.
 *
 * <p>Highest priority: if the caller already knows the exact partition count it wins
 * over any workload or capacity estimate.  Recognised parameter keys (in priority order):
 * {@code partitionCount}, {@code estimatedPartitionCount}, {@code suggestedPartitionCount},
 * {@code shardCount}.
 */
@Component
@Order(1)
public class ExplicitPartitionCountResolver implements PartitionCountResolver {

    @Override
    public int resolve(JobDefinitionRecord jobDefinition, Map<String, Object> params, ShardStrategy shardStrategy) {
        return firstPositiveInt(
                params.get("partitionCount"),
                params.get("estimatedPartitionCount"),
                params.get("suggestedPartitionCount"),
                params.get("shardCount")
        );
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
            return 0;
        }
    }
}
