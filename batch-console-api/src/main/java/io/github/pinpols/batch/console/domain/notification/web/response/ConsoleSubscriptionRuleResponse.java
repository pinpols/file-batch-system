package io.github.pinpols.batch.console.domain.notification.web.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Map;

/**
 * 订阅规则行响应（{@code SubscriptionRuleMapper.selectByTenant/selectById} 投影）。
 *
 * <p><b>wire 红线</b>：mapper 显式列出 snake_case 列且不带别名，历史响应键为 snake_case（{@code rule_name} / {@code
 * event_types} 等）。每个多词字段显式 {@code @JsonProperty} 回 snake_case，JSON 键一字不差。 {@code event_types /
 * severity_filter / job_code_filter} 均为文本列（逗号分隔白名单），原样透传为 {@code String}。
 *
 * <p>MyBatis {@code resultType="map"} 省略 null 列 → {@code NON_NULL} 保键集对等。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConsoleSubscriptionRuleResponse(
    Long id,
    @JsonProperty("tenant_id") String tenantId,
    @JsonProperty("rule_name") String ruleName,
    @JsonProperty("channel_code") String channelCode,
    @JsonProperty("event_types") String eventTypes,
    @JsonProperty("severity_filter") String severityFilter,
    @JsonProperty("job_code_filter") String jobCodeFilter,
    Boolean enabled,
    @JsonProperty("created_by") String createdBy,
    @JsonProperty("updated_by") String updatedBy,
    @JsonProperty("created_at") Instant createdAt,
    @JsonProperty("updated_at") Instant updatedAt) {

  public static ConsoleSubscriptionRuleResponse from(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    return new ConsoleSubscriptionRuleResponse(
        NotificationResponseFieldReader.longValue(row, "id"),
        NotificationResponseFieldReader.stringValue(row, "tenant_id"),
        NotificationResponseFieldReader.stringValue(row, "rule_name"),
        NotificationResponseFieldReader.stringValue(row, "channel_code"),
        NotificationResponseFieldReader.stringValue(row, "event_types"),
        NotificationResponseFieldReader.stringValue(row, "severity_filter"),
        NotificationResponseFieldReader.stringValue(row, "job_code_filter"),
        NotificationResponseFieldReader.booleanValue(row, "enabled"),
        NotificationResponseFieldReader.stringValue(row, "created_by"),
        NotificationResponseFieldReader.stringValue(row, "updated_by"),
        NotificationResponseFieldReader.instantValue(row, "created_at"),
        NotificationResponseFieldReader.instantValue(row, "updated_at"));
  }
}
