package com.example.batch.orchestrator.domain.entity;

import java.time.Instant;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Table("batch.tenant_scheduler_snapshot")
public class TenantSchedulerSnapshotRecord {

    @Id
    private Long id;
    @Column("tenant_id")
    private String tenantId;
    @Column("snapshot_at")
    private Instant snapshotAt;
    @Column("fair_share_group")
    private String fairShareGroup;
    @Column("policy_code")
    private String policyCode;
    @Column("active_jobs")
    private Integer activeJobs;
    @Column("active_partitions")
    private Integer activePartitions;
    @Column("max_jobs_base")
    private Integer maxJobsBase;
    @Column("burst_limit")
    private Integer burstLimit;
    @Column("effective_job_cap")
    private Integer effectiveJobCap;
    @Column("group_active_jobs")
    private Integer groupActiveJobs;
    @Column("group_max_jobs")
    private Integer groupMaxJobs;
    @Column("quota_reset_policy")
    private String quotaResetPolicy;
    @Column("online_workers")
    private Integer onlineWorkers;
    @Column("detail_json")
    private String detailJson;
}
