package io.github.pinpols.batch.console.domain.ops.web.response;

import static io.github.pinpols.batch.console.domain.ops.web.response.OpsResponseFieldReader.asMap;
import static io.github.pinpols.batch.console.domain.ops.web.response.OpsResponseFieldReader.instantValue;
import static io.github.pinpols.batch.console.domain.ops.web.response.OpsResponseFieldReader.longValue;
import static io.github.pinpols.batch.console.domain.ops.web.response.OpsResponseFieldReader.stringValue;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/**
 * ConfigRelease 审批详情响应（submit / approve / reject / detail 四端点同构，均 return detail(...)）。
 *
 * <p>{@code approval} 为最新审批行；无审批时历史 wire 保留显式 {@code "approval": null} 键 → 顶层不加 {@code NON_NULL}。嵌套
 * {@link ApprovalRow} 来自 MyBatis {@code resultType=map}（省略 null 列，如 pending 时无
 * reviewedBy/reviewedAt）→ 加 {@code NON_NULL} 保键集对等。
 */
public record ConsoleConfigApprovalDetailResponse(
    Long releaseId,
    String tenantId,
    String configType,
    String configKey,
    String configStatus,
    ApprovalRow approval) {

  /** 单条 config_approval 行投影（键为 mapper 别名 camelCase）。 */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ApprovalRow(
      Long id,
      String tenantId,
      Long releaseId,
      String approvalStatus,
      String requestedBy,
      Instant requestedAt,
      String reviewedBy,
      Instant reviewedAt,
      String reviewComment,
      Instant expiredAt,
      Instant createdAt,
      Instant updatedAt) {

    static ApprovalRow from(Map<String, Object> row) {
      if (row == null) {
        return null;
      }
      return new ApprovalRow(
          longValue(row, "id"),
          stringValue(row, "tenantId"),
          longValue(row, "releaseId"),
          stringValue(row, "approvalStatus"),
          stringValue(row, "requestedBy"),
          instantValue(row, "requestedAt"),
          stringValue(row, "reviewedBy"),
          instantValue(row, "reviewedAt"),
          stringValue(row, "reviewComment"),
          instantValue(row, "expiredAt"),
          instantValue(row, "createdAt"),
          instantValue(row, "updatedAt"));
    }
  }

  public static ConsoleConfigApprovalDetailResponse from(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    return new ConsoleConfigApprovalDetailResponse(
        longValue(row, "releaseId"),
        stringValue(row, "tenantId"),
        stringValue(row, "configType"),
        stringValue(row, "configKey"),
        stringValue(row, "configStatus"),
        ApprovalRow.from(asMap(row.get("approval"))));
  }
}
