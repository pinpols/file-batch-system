package com.example.batch.console.domain.param;

import lombok.Data;

@Data
public class WorkflowDefinitionUpsertParam {

  private String tenantId;
  private String workflowCode;
  private String workflowName;
  private String workflowType;
  private Integer version;
  private Boolean enabled;
  private String description;
  private String createdBy;
  private String updatedBy;
}
