package io.github.pinpols.batch.console.domain.ops.application.contract.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import lombok.Builder;

/**
 * ConfigRelease 审批详情响应（submit / approve / reject / detail 四端点同构，均 return detail(...)）。
 *
 * <p>{@code approval} 为最新审批行；无审批时保留显式 {@code "approval": null} 键。嵌套审批行省略空字段，保持历史响应键集。
 */
public record ConsoleConfigApprovalDetailResponse(
    Long releaseId,
    String tenantId,
    String configType,
    String configKey,
    String configStatus,
    ApprovalRow approval) {

  /** 单条 config_approval 行的响应投影。 */
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
      Instant updatedAt) {}
}
