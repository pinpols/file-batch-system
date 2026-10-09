package io.github.pinpols.batch.console.domain.job.param;

import java.time.LocalTime;
import lombok.Data;

@Data
public class JobDefinitionMaintenanceUpdateParam {

  private String tenantId;
  private String jobCode;
  private String dependsOnJobCode;
  private String jobName;
  private String queueCode;
  private String workerGroup;
  private String scheduleExpr;
  private String calendarCode;
  private String windowCode;
  private String retryPolicy;
  private Integer retryMaxCount;
  private Integer timeoutSeconds;
  private Integer softRuntimeSeconds;
  private String softRuntimeSeverity;
  private Integer startGraceSeconds;
  private String startGraceSeverity;
  private LocalTime completionDeadlineLocalTime;
  private Integer completionDeadlineDayOffset;
  private String completionDeadlineSeverity;
  private Integer dependencyCompletionWindowSeconds;
  private String shardStrategy;
  private String executionMode;
  private String watermarkField;
  private Boolean enabled;
  private String description;
  private String updatedBy;
}
