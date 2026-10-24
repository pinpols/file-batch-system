package io.github.pinpols.batch.console.application.contract.response.config;

import static io.github.pinpols.batch.console.application.contract.response.config.ResponseFieldReader.booleanValue;
import static io.github.pinpols.batch.console.application.contract.response.config.ResponseFieldReader.instantValue;
import static io.github.pinpols.batch.console.application.contract.response.config.ResponseFieldReader.integerValue;
import static io.github.pinpols.batch.console.application.contract.response.config.ResponseFieldReader.longValue;
import static io.github.pinpols.batch.console.application.contract.response.config.ResponseFieldReader.stringValue;

import java.time.Instant;
import java.util.Map;
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

  public static QuotaPolicyResponse from(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    return QuotaPolicyResponse.builder()
        .id(longValue(row, "id"))
        .tenantId(stringValue(row, "tenantId", "tenant_id"))
        .policyCode(stringValue(row, "policyCode", "policy_code"))
        .maxRunningJobsPerTenant(integerValue(row, "maxRunningJobsPerTenant", "max_running_jobs_per_tenant"))
        .maxPartitionsPerTenant(integerValue(row, "maxPartitionsPerTenant", "max_partitions_per_tenant"))
        .maxQpsPerTenant(integerValue(row, "maxQpsPerTenant", "max_qps_per_tenant"))
        .fairShareWeight(integerValue(row, "fairShareWeight", "fair_share_weight"))
        .enabled(booleanValue(row, "enabled"))
        .description(stringValue(row, "description"))
        .createdAt(instantValue(row, "createdAt", "created_at"))
        .updatedAt(instantValue(row, "updatedAt", "updated_at"))
        .build();
  }
}
