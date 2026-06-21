package com.example.batch.console.web.query;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ApprovalCommandQueryRequest extends PageQueryRequest {

  @NotBlank private String tenantId;
  private String approvalNo;
  private String approvalType;
  private String actionType;
  private String approvalStatus;

  /** 申请人精确过滤(前端 ?requester=me 入口)。 */
  @Size(max = 128, message = "requesterId too long (max 128)")
  private String requesterId;

  /** 关键字模糊过滤(大小写不敏感):匹配 approval_no / requester_id / target_type / target_id 任一。 */
  @Size(max = 128, message = "keyword too long (max 128)")
  private String keyword;
}
