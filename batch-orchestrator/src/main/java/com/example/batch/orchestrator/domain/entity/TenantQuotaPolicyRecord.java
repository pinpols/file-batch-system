package com.example.batch.orchestrator.domain.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table(schema = "batch", value = "tenant_quota_policy")
public record TenantQuotaPolicyRecord(
    @Id Long id,
    @Column("tenant_id") String tenantId,
    @Column("policy_code") String policyCode,
    @Column("max_running_jobs_per_tenant") Integer maxRunningJobsPerTenant,
    @Column("max_partitions_per_tenant") Integer maxPartitionsPerTenant,
    @Column("max_qps_per_tenant") Integer maxQpsPerTenant,
    @Column("fair_share_weight") Integer fairShareWeight,
    @Column("fair_share_group") String fairShareGroup,
    @Column("burst_limit") Integer burstLimit,
    @Column("partition_burst_limit") Integer partitionBurstLimit,
    @Column("quota_reset_policy") String quotaResetPolicy,
    @Column("group_shared_max_running_jobs") Integer groupSharedMaxRunningJobs,
    @Column("enabled") Boolean enabled) {}
