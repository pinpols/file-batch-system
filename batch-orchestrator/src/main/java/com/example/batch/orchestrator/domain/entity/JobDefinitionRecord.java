package com.example.batch.orchestrator.domain.entity;

import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table(schema = "batch", value = "job_definition")
public record JobDefinitionRecord(
        @Id Long id,
        @Column("tenant_id") String tenantId,
        @Column("job_code") String jobCode,
        @Column("job_name") String jobName,
        @Column("job_type") String jobType,
        @Column("biz_type") String bizType,
        @Column("schedule_type") String scheduleType,
        @Column("schedule_expr") String scheduleExpr,
        @Column("timezone") String timezone,
        @Column("worker_group") String workerGroup,
        @Column("queue_code") String queueCode,
        @Column("calendar_code") String calendarCode,
        @Column("window_code") String windowCode,
        @Column("trigger_mode") String triggerMode,
        @Column("dag_enabled") Boolean dagEnabled,
        @Column("shard_strategy") String shardStrategy,
        @Column("retry_policy") String retryPolicy,
        @Column("retry_max_count") Integer retryMaxCount,
        @Column("timeout_seconds") Integer timeoutSeconds,
        @Column("execution_handler") String executionHandler,
        @Column("param_schema") Map<String, Object> paramSchema,
        @Column("priority") Integer priority,
        @Column("default_params") Map<String, Object> defaultParams,
        @Column("version") Integer version,
        @Column("enabled") Boolean enabled,
        @Column("description") String description
) {}
