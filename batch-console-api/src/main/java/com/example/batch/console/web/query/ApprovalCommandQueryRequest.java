package com.example.batch.console.web.query;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ApprovalCommandQueryRequest extends PageQueryRequest {

  @NotBlank private String tenantId;
  private String approvalNo;
  private String approvalType;
  private String actionType;
  private String approvalStatus;
}
