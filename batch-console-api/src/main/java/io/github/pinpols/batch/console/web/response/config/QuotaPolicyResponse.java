package io.github.pinpols.batch.console.web.response.config;

import static io.github.pinpols.batch.console.web.response.config.ResponseFieldReader.booleanValue;
import static io.github.pinpols.batch.console.web.response.config.ResponseFieldReader.instantValue;
import static io.github.pinpols.batch.console.web.response.config.ResponseFieldReader.integerValue;
import static io.github.pinpols.batch.console.web.response.config.ResponseFieldReader.longValue;
import static io.github.pinpols.batch.console.web.response.config.ResponseFieldReader.stringValue;

import java.time.Instant;
import java.util.Map;

/** 租户配额策略响应。 */
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
    return new QuotaPolicyResponse(
        longValue(row, "id"),
        stringValue(row, "tenantId", "tenant_id"),
        stringValue(row, "policyCode", "policy_code"),
        integerValue(row, "maxRunningJobsPerTenant", "max_running_jobs_per_tenant"),
        integerValue(row, "maxPartitionsPerTenant", "max_partitions_per_tenant"),
        integerValue(row, "maxQpsPerTenant", "max_qps_per_tenant"),
        integerValue(row, "fairShareWeight", "fair_share_weight"),
        booleanValue(row, "enabled"),
        stringValue(row, "description"),
        instantValue(row, "createdAt", "created_at"),
        instantValue(row, "updatedAt", "updated_at"));
  }
}
