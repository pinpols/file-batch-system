package io.github.pinpols.batch.console.domain.job.application.contract.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.domain.job.view.ConsoleJobConfigViews.BatchWindowRow;
import java.time.Instant;
import lombok.Builder;

/**
 * 批量窗口响应（BatchWindowMapper 行投影）。
 *
 * <p><b>wire 红线</b>：该 mapper 的 {@code selectByQuery} / {@code select *} 均<b>不带列别名</b>，历史响应键是
 * snake_case（{@code window_code} 等）。故每个多词字段显式 {@code @JsonProperty} 回 snake_case，保持 JSON 键一字不差。
 * {@code startTime/endTime} 为 TIME 列，统一为 {@code HH:mm[:ss]} 字符串。
 *
 * <p>历史 Map 响应会省略 null 列，使用 {@code NON_NULL} 保持相同键集。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder
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

  public static ConsoleBatchWindowResponse from(BatchWindowRow row) {
    if (EmptyChecks.isNull(row)) {
      return null;
    }
    return ConsoleBatchWindowResponse.builder()
        .id(row.id())
        .tenantId(row.tenantId())
        .windowCode(row.windowCode())
        .windowName(row.windowName())
        .timezone(row.timezone())
        .startTime(row.startTime())
        .endTime(row.endTime())
        .endStrategy(row.endStrategy())
        .outOfWindowAction(row.outOfWindowAction())
        .allowCrossDay(row.allowCrossDay())
        .enabled(row.enabled())
        .description(row.description())
        .createdAt(row.createdAt())
        .updatedAt(row.updatedAt())
        .build();
  }
}
