package com.example.batch.orchestrator.application.plan;

import com.example.batch.common.enums.ShardStrategy;
import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.orchestrator.domain.entity.JobDefinitionRecord;
import com.example.batch.orchestrator.repository.WorkerRegistryRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Resolves partition count from the number of currently online workers.
 *
 * <p>Formula: {@code min(256, onlineWorkerCount × partitionFactor)}.
 * The partition factor defaults to {@code 2} for DYNAMIC strategy and {@code 1} otherwise.
 * Returns {@code 0} when no online workers can be found.
 */
@Component
@Order(4)
@RequiredArgsConstructor
public class WorkerBasedPartitionCountResolver implements PartitionCountResolver {

    private final WorkerRegistryRepository workerRegistryRepository;

    @Override
    public int resolve(JobDefinitionRecord jobDefinition, Map<String, Object> params, ShardStrategy shardStrategy) {
        long onlineWorkerCount = resolveOnlineWorkerCount(jobDefinition, params);
        if (onlineWorkerCount <= 0) {
            return 0;
        }
        int partitionFactor = firstPositiveInt(
                params.get("partitionFactor"),
                params.get("workerPartitionFactor"),
                shardStrategy == ShardStrategy.DYNAMIC ? 2 : 1
        );
        return (int) Math.min(256L, onlineWorkerCount * partitionFactor);
    }

    private long resolveOnlineWorkerCount(JobDefinitionRecord jobDefinition, Map<String, Object> params) {
        if (jobDefinition == null || !StringUtils.hasText(jobDefinition.tenantId())) {
            return firstPositiveLong(params.get("onlineWorkerCount"), params.get("workerCount"));
        }
        if (StringUtils.hasText(jobDefinition.workerGroup())) {
            return workerRegistryRepository.countByTenantIdAndWorkerGroupAndStatus(
                    jobDefinition.tenantId(),
                    jobDefinition.workerGroup(),
                    WorkerRegistryStatus.ONLINE.code()
            );
        }
        return workerRegistryRepository.countByTenantIdAndStatus(
                jobDefinition.tenantId(),
                WorkerRegistryStatus.ONLINE.code()
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
