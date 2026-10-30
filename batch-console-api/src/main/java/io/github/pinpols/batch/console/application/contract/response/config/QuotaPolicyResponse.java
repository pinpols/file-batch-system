package io.github.pinpols.batch.console.application.contract.response.config;

import io.github.pinpols.batch.console.domain.rbac.view.TenantQuotaPolicyRow;
import java.time.Instant;
import lombok.Builder;

/** 租户配额策略响应。 */
@Builder
public record QuotaPolicyResponse(
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
    Instant updatedAt) {

  public static QuotaPolicyResponse from(TenantQuotaPolicyRow row) {
    if (row == null) {
      return null;
    }
    return QuotaPolicyResponse.builder()
        .id(row.id())
        .tenantId(row.tenantId())
        .policyCode(row.policyCode())
        .maxRunningJobsPerTenant(row.maxRunningJobsPerTenant())
        .maxPartitionsPerTenant(row.maxPartitionsPerTenant())
        .maxQpsPerTenant(row.maxQpsPerTenant())
        .fairShareWeight(row.fairShareWeight())
        .enabled(row.enabled())
        .description(row.description())
        .createdAt(row.createdAt())
        .updatedAt(row.updatedAt())
        .build();
  }
}
