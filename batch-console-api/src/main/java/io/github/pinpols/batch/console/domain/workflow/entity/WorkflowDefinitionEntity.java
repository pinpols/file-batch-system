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
  private Long singleJobDefinitionId;
  private String scheduleType;
  private String scheduleExpr;
  private String timezone;
  private String triggerMode;
  private String calendarCode;
  private String dependsOnJobCode;
  private Integer version;
  private Boolean enabled;
  private Boolean scheduleEnabled;
  private String description;
  private Instant createdAt;
  private Instant updatedAt;
}
