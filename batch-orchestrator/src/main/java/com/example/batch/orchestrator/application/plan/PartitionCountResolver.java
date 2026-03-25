package com.example.batch.orchestrator.application.plan;

import com.example.batch.common.enums.ShardStrategy;
import com.example.batch.orchestrator.domain.entity.JobDefinitionRecord;
import java.util.Map;

/**
 * Strategy interface for resolving the number of partitions in a dynamic shard plan.
 *
 * <p>Each implementation encapsulates one resolution approach (explicit override,
 * size-based estimate, runtime-based estimate, online-worker-based capacity).
 * {@link DefaultSchedulePlanBuilder} chains multiple resolvers; the first one that
 * returns a positive value wins.  The resolver chain replaces the original
 * 5-level nested conditional inside {@code resolveDynamicPartitionCount()}.
 */
public interface PartitionCountResolver {

    /**
     * @return a positive partition count if this resolver can provide one,
     *         or {@code 0} if this resolver is not applicable for the given inputs.
     */
    int resolve(JobDefinitionRecord jobDefinition, Map<String, Object> params, ShardStrategy shardStrategy);
}
