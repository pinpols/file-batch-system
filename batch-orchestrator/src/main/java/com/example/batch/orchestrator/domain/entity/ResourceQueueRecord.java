package com.example.batch.orchestrator.domain.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table(schema = "batch", value = "resource_queue")
public record ResourceQueueRecord(
        @Id Long id,
        @Column("tenant_id") String tenantId,
        @Column("queue_code") String queueCode,
        @Column("queue_name") String queueName,
        @Column("queue_type") String queueType,
        @Column("max_running_jobs") Integer maxRunningJobs,
        @Column("max_running_partitions") Integer maxRunningPartitions,
        @Column("max_qps") Integer maxQps,
        @Column("worker_group") String workerGroup,
        @Column("resource_tag") String resourceTag,
        @Column("priority_policy") String priorityPolicy,
        @Column("fair_share_weight") Integer fairShareWeight,
        @Column("fair_share_group") String fairShareGroup,
        @Column("burst_limit") Integer burstLimit,
        @Column("quota_reset_policy") String quotaResetPolicy,
        @Column("group_shared_max_running_jobs") Integer groupSharedMaxRunningJobs,
        @Column("enabled") Boolean enabled) {}
