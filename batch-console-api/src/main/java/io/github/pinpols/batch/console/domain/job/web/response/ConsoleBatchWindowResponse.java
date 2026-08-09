package io.github.pinpols.batch.console.domain.job.web.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.pinpols.batch.console.support.web.ConsoleResponseFieldReader;
import java.time.Instant;
import java.util.Map;

/**
 * 批量窗口响应（BatchWindowMapper 行投影）。
 *
 * <p><b>wire 红线</b>：该 mapper 的 {@code selectByQuery} / {@code select *} 均<b>不带列别名</b>，历史响应键是
 * snake_case（{@code window_code} 等）。故每个多词字段显式 {@code @JsonProperty} 回 snake_case，保持 JSON 键一字不差。
 * {@code startTime/endTime} 为 TIME 列，统一为 {@code HH:mm[:ss]} 字符串。
 *
 * <p>MyBatis {@code resultType="map"} 会省略 null 列，历史 wire 不含 null 键 → {@code NON_NULL} 保键集对等。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConsoleBatchWindowResponse(
    Long id,
    @JsonProperty("tenant_id") String tenantId,
    @JsonProperty("window_code") String windowCode,
    @JsonProperty("window_name") String windowName,
    String timezone,
    @JsonProperty("start_time") String startTime,
    @JsonProperty("end_time") String endTime,
    @JsonProperty("end_strategy") String endStrategy,
    @JsonProperty("out_of_window_action") String outOfWindowAction,
    @JsonProperty("allow_cross_day") Boolean allowCrossDay,
    Boolean enabled,
    String description,
    @JsonProperty("created_at") Instant createdAt,
    @JsonProperty("updated_at") Instant updatedAt) {

  public static ConsoleBatchWindowResponse from(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    return new ConsoleBatchWindowResponse(
        ConsoleResponseFieldReader.longValue(row, "id"),
        ConsoleResponseFieldReader.stringValue(row, "tenant_id", "tenantId"),
        ConsoleResponseFieldReader.stringValue(row, "window_code", "windowCode"),
        ConsoleResponseFieldReader.stringValue(row, "window_name", "windowName"),
        ConsoleResponseFieldReader.stringValue(row, "timezone"),
        ConsoleResponseFieldReader.localTimeValue(row, "start_time", "startTime"),
        ConsoleResponseFieldReader.localTimeValue(row, "end_time", "endTime"),
        ConsoleResponseFieldReader.stringValue(row, "end_strategy", "endStrategy"),
        ConsoleResponseFieldReader.stringValue(row, "out_of_window_action", "outOfWindowAction"),
        ConsoleResponseFieldReader.booleanValue(row, "allow_cross_day", "allowCrossDay"),
        ConsoleResponseFieldReader.booleanValue(row, "enabled"),
        ConsoleResponseFieldReader.stringValue(row, "description"),
        ConsoleResponseFieldReader.instantValue(row, "created_at", "createdAt"),
        ConsoleResponseFieldReader.instantValue(row, "updated_at", "updatedAt"));
  }
}
