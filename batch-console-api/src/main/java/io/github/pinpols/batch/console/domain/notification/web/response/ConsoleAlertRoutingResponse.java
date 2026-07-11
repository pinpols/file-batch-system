package io.github.pinpols.batch.console.domain.notification.web.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Map;

/**
 * 告警路由配置行响应（{@code AlertRoutingConfigMapper} 投影）。
 *
 * <p><b>wire 红线</b>：mapper 列不带别名，历史响应键为 snake_case（{@code route_code} 等）。每个多词字段显式
 * {@code @JsonProperty} 回 snake_case。{@code alert_routing_config} 全列均为标量（无 jsonb），列集完全已知，故按 {@code
 * select *} 全列枚举为一个 record，同时服务 list / create / update 三个端点：
 *
 * <ul>
 *   <li>{@code list}（{@code selectByQuery} 显式列，不含 {@code created_by/updated_by/is_deleted}）；
 *   <li>{@code create/update}（{@code selectByUniqueKey/selectById} 为 {@code select *}，含全部列）。
 * </ul>
 *
 * <p>MyBatis {@code resultType="map"} 省略 null 列，{@code NON_NULL} 使各端点缺省的列自动省略 → 与各自历史 wire 键集逐一对等。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConsoleAlertRoutingResponse(
    Long id,
    @JsonProperty("tenant_id") String tenantId,
    @JsonProperty("route_code") String routeCode,
    @JsonProperty("route_name") String routeName,
    String team,
    @JsonProperty("alert_group") String alertGroup,
    String severity,
    String receiver,
    @JsonProperty("group_by") String groupBy,
    @JsonProperty("group_wait_seconds") Integer groupWaitSeconds,
    @JsonProperty("group_interval_seconds") Integer groupIntervalSeconds,
    @JsonProperty("repeat_interval_seconds") Integer repeatIntervalSeconds,
    Boolean enabled,
    String description,
    @JsonProperty("created_by") String createdBy,
    @JsonProperty("updated_by") String updatedBy,
    @JsonProperty("created_at") Instant createdAt,
    @JsonProperty("updated_at") Instant updatedAt,
    @JsonProperty("is_deleted") Boolean isDeleted) {

  public static ConsoleAlertRoutingResponse from(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    return new ConsoleAlertRoutingResponse(
        NotificationResponseFieldReader.longValue(row, "id"),
        NotificationResponseFieldReader.stringValue(row, "tenant_id"),
        NotificationResponseFieldReader.stringValue(row, "route_code"),
        NotificationResponseFieldReader.stringValue(row, "route_name"),
        NotificationResponseFieldReader.stringValue(row, "team"),
        NotificationResponseFieldReader.stringValue(row, "alert_group"),
        NotificationResponseFieldReader.stringValue(row, "severity"),
        NotificationResponseFieldReader.stringValue(row, "receiver"),
        NotificationResponseFieldReader.stringValue(row, "group_by"),
        NotificationResponseFieldReader.integerValue(row, "group_wait_seconds"),
        NotificationResponseFieldReader.integerValue(row, "group_interval_seconds"),
        NotificationResponseFieldReader.integerValue(row, "repeat_interval_seconds"),
        NotificationResponseFieldReader.booleanValue(row, "enabled"),
        NotificationResponseFieldReader.stringValue(row, "description"),
        NotificationResponseFieldReader.stringValue(row, "created_by"),
        NotificationResponseFieldReader.stringValue(row, "updated_by"),
        NotificationResponseFieldReader.instantValue(row, "created_at"),
        NotificationResponseFieldReader.instantValue(row, "updated_at"),
        NotificationResponseFieldReader.booleanValue(row, "is_deleted"));
  }
}
