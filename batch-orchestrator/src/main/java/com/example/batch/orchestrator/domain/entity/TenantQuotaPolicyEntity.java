package com.example.batch.orchestrator.domain.entity;

public record TenantQuotaPolicyEntity(
    Long id,
    String tenantId,
    String policyCode,
    Integer maxRunningJobsPerTenant,
    Integer maxPartitionsPerTenant,
    Integer maxQpsPerTenant,
    Integer fairShareWeight,
    String fairShareGroup,
    Integer burstLimit,
    Integer partitionBurstLimit,
    String quotaResetPolicy,
    Integer groupSharedMaxRunningJobs,
    Boolean enabled,
    String exceededStrategy) {}
