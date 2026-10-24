package io.github.pinpols.batch.console.domain.ops.application.contract.response;

import static io.github.pinpols.batch.console.domain.ops.application.contract.response.OpsResponseFieldReader.asMap;
import static io.github.pinpols.batch.console.domain.ops.application.contract.response.OpsResponseFieldReader.instantValue;
import static io.github.pinpols.batch.console.domain.ops.application.contract.response.OpsResponseFieldReader.longValue;
import static io.github.pinpols.batch.console.domain.ops.application.contract.response.OpsResponseFieldReader.stringValue;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;
import lombok.Builder;

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
  @Builder
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
      return ApprovalRow.builder()
          .id(longValue(row, "id"))
          .tenantId(stringValue(row, "tenantId"))
          .releaseId(longValue(row, "releaseId"))
          .approvalStatus(stringValue(row, "approvalStatus"))
          .requestedBy(stringValue(row, "requestedBy"))
          .requestedAt(instantValue(row, "requestedAt"))
          .reviewedBy(stringValue(row, "reviewedBy"))
          .reviewedAt(instantValue(row, "reviewedAt"))
          .reviewComment(stringValue(row, "reviewComment"))
          .expiredAt(instantValue(row, "expiredAt"))
          .createdAt(instantValue(row, "createdAt"))
          .updatedAt(instantValue(row, "updatedAt"))
          .build();
    }
  }

  public static ConsoleConfigApprovalDetailResponse from(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    Long releaseId = longValue(row, "releaseId");
    String tenantId = stringValue(row, "tenantId");
    String configType = stringValue(row, "configType");
    String configKey = stringValue(row, "configKey");
    String configStatus = stringValue(row, "configStatus");
    Map<String, Object> approvalFields = asMap(row.get("approval"));
    ApprovalRow approval = ApprovalRow.from(approvalFields);
    return new ConsoleConfigApprovalDetailResponse(
        releaseId, tenantId, configType, configKey, configStatus, approval);
  }
}
