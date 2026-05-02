package com.example.batch.console.domain.param;

import lombok.Data;

@Data
public class TenantUpsertParam {
  private String tenantId;
  private String tenantName;
  private String status;
  private String description;
  private String createdBy;
}
