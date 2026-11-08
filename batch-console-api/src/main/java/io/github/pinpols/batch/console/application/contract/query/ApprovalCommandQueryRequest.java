package io.github.pinpols.batch.console.application.contract.query;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ApprovalCommandQueryRequest extends PageQueryRequest {

  @NotBlank
  private String tenantId;

  private String approvalNo;
  private String approvalType;
  private String actionType;
  private String approvalStatus;

  /**
   * 逗号分隔的审批类型排除列表，最多 8 个类型代码。普通审批列表用 {@code CATCH_UP} 去掉补跑单。与 {@code
   * approvalType} 同时出现时两者都生效。
   */
  @Size(max = 512, message = "excludeApprovalTypes too long (max 512)")
  private String excludeApprovalTypes;

  /** 申请人精确过滤(前端 ?requester=me 入口)。 */
  @Size(max = 128, message = "requesterId too long (max 128)")
  private String requesterId;

  /** 关键字模糊过滤(大小写不敏感):匹配 approval_no / requester_id / target_type / target_id 任一。 */
  @Size(max = 128, message = "keyword too long (max 128)")
  private String keyword;
}
