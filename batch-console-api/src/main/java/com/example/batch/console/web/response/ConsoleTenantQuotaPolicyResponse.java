package com.example.batch.console.web.response;

import java.time.Instant;

public record ConsoleTenantQuotaPolicyResponse(
        Long id,
        String tenantId,
        String policyCode,
        Integer maxRunningJobsPerTenant,
        Integer maxPartitionsPerTenant,
        Integer maxQpsPerTenant,
        Integer fairShareWeight,
        Boolean enabled,
        String description,
        Instant createdAt,
        Instant updatedAt) {}
