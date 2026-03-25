package com.example.batch.orchestrator.application.plan;

import com.example.batch.common.enums.ShardStrategy;
import com.example.batch.orchestrator.domain.entity.JobDefinitionRecord;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Resolves partition count from estimated data volume.
 *
 * <p>Supports two workload signals (tried in order):
 * <ol>
 *   <li>Item count ÷ target items-per-partition</li>
 *   <li>File size (bytes) ÷ target bytes-per-partition</li>
 * </ol>
 * Returns {@code 0} when the necessary parameters are absent or non-positive.
 */
@Component
@Order(2)
public class SizeBasedPartitionCountResolver implements PartitionCountResolver {

    @Override
    public int resolve(JobDefinitionRecord jobDefinition, Map<String, Object> params, ShardStrategy shardStrategy) {
        long estimatedItems = firstPositiveLong(
                params.get("estimatedItemCount"),
                params.get("recordCount"),
                params.get("itemCount"),
                params.get("totalCount")
        );
        int targetItemsPerPartition = firstPositiveInt(
                params.get("targetItemsPerPartition"),
                params.get("targetShardSize"),
                params.get("itemsPerPartition")
        );
        if (estimatedItems > 0 && targetItemsPerPartition > 0) {
            return ceilDiv(estimatedItems, targetItemsPerPartition);
        }

        long estimatedBytes = firstPositiveLong(
                params.get("estimatedFileSizeBytes"),
                params.get("fileSizeBytes"),
                params.get("sourceFileSizeBytes")
        );
        long targetBytesPerPartition = firstPositiveLong(
                params.get("targetBytesPerPartition"),
                params.get("targetShardBytes")
        );
        if (estimatedBytes > 0 && targetBytesPerPartition > 0) {
            return ceilDiv(estimatedBytes, targetBytesPerPartition);
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

    private int ceilDiv(long dividend, long divisor) {
        if (dividend <= 0 || divisor <= 0) {
            return 1;
        }
        return (int) ((dividend + divisor - 1) / divisor);
    }
}
