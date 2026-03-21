package com.example.batch.orchestrator.domain.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Table("batch.tenant_quota_policy")
public class TenantQuotaPolicyRecord {

    @Id
    private Long id;
    @Column("tenant_id")
    private String tenantId;
    @Column("policy_code")
    private String policyCode;
    @Column("max_running_jobs_per_tenant")
    private Integer maxRunningJobsPerTenant;
    @Column("max_partitions_per_tenant")
    private Integer maxPartitionsPerTenant;
    @Column("max_qps_per_tenant")
    private Integer maxQpsPerTenant;
    @Column("fair_share_weight")
    private Integer fairShareWeight;
    @Column("enabled")
    private Boolean enabled;
}
