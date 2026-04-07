package com.example.batch.orchestrator.application.plan;

import com.example.batch.common.enums.ShardStrategy;
import com.example.batch.orchestrator.domain.entity.JobDefinitionRecord;
import java.util.Map;

/**
 * 动态分片计划中分区数的解析策略接口。
 * 每个实现封装一种解析方式（显式覆盖、数据量估算、历史时长估算、在线 Worker 容量）。
 * {@link DefaultSchedulePlanBuilder} 链式调用多个解析器，第一个返回正值的结果生效。
 */
public interface PartitionCountResolver {

    /** 能解析时返回正整数分区数，不适用时返回 {@code 0}。 */
    int resolve(JobDefinitionRecord jobDefinition, Map<String, Object> params, ShardStrategy shardStrategy);
}
