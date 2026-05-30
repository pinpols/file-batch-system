package com.example.batch.console.domain.ops.query;

import com.example.batch.common.model.PageRequest;
import lombok.Data;

@Data
public class ApprovalCommandQuery {

  private String tenantId;
  private String approvalNo;
  private String approvalType;
  private String actionType;
  private String approvalStatus;
  private PageRequest pageRequest;
}
