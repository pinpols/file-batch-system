package com.example.batch.trigger.domain.command;

import lombok.Data;

@Data
public class PendingCatchUpApprovalCommand {

  private String tenantId;
  private String requestId;
  private String reason;
}
