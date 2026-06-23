package io.github.pinpols.batch.console.domain.workflow.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class WorkflowDefinitionEntity {

  private Long id;
  private String tenantId;
  private String workflowCode;
  private String workflowName;
  private String workflowType;
  private Integer version;
  private Boolean enabled;
  private String description;
  private Instant createdAt;
  private Instant updatedAt;
}
