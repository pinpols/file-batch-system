package com.example.batch.orchestrator.domain.entity;

import com.example.batch.orchestrator.domain.value.JsonbString;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table(schema = "batch", value = "tenant_scheduler_snapshot")
public record TenantSchedulerSnapshotRecord(
        @Id Long id,
        @Column("tenant_id") String tenantId,
        @Column("snapshot_at") Instant snapshotAt,
        @Column("fair_share_group") String fairShareGroup,
        @Column("policy_code") String policyCode,
        @Column("active_jobs") Integer activeJobs,
        @Column("active_partitions") Integer activePartitions,
        @Column("max_jobs_base") Integer maxJobsBase,
        @Column("burst_limit") Integer burstLimit,
        @Column("effective_job_cap") Integer effectiveJobCap,
        @Column("group_active_jobs") Integer groupActiveJobs,
        @Column("group_max_jobs") Integer groupMaxJobs,
        @Column("quota_reset_policy") String quotaResetPolicy,
        @Column("online_workers") Integer onlineWorkers,
        @Column("detail_json") JsonbString detailJson
) {}
