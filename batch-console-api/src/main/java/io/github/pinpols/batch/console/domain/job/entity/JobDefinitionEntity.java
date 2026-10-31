package io.github.pinpols.batch.console.domain.job.entity;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.domain.job.application.contract.response.JobDependencyRuleResponse;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import lombok.Data;

@Data
public class JobDefinitionEntity {

  private static final String DEPENDENCY_CODE_DELIMITER = String.valueOf((char) 31);

  private Long id;
  private String tenantId;
  private String jobCode;
  private String dependsOnJobCode;
  private List<String> dependsOnJobCodes;
  private String dependsOnJobCodesCsv;
  private List<JobDependencyRuleResponse> jobDependencyRules = List.of();

  public void setDependencyRulesJson(String value) {
    jobDependencyRules = EmptyChecks.isBlank(value)
        ? List.of()
        : JsonUtils.fromJson(value, new TypeReference<List<JobDependencyRuleResponse>>() {});
  }

  public void setDependsOnJobCodesCsv(String value) {
    dependsOnJobCodes = EmptyChecks.isBlank(value)
        ? List.of()
        : Arrays.stream(value.split(DEPENDENCY_CODE_DELIMITER))
            .map(String::trim)
            .filter(code -> EmptyChecks.isNotEmpty(code))
            .toList();
  }

  private String jobName;
  private String jobType;
  private String bizType;
  private String queueCode;
  private String workerGroup;
  private String scheduleType;
  private String scheduleExpr;
  private String timezone;
  private String calendarCode;
  private String windowCode;
  private String triggerMode;
  private Boolean dagEnabled;
  private String retryPolicy;
  private Integer retryMaxCount;
  private Integer timeoutSeconds;
  private Integer softRuntimeSeconds;
  private String softRuntimeSeverity;
  private Integer startGraceSeconds;
  private String startGraceSeverity;
  private Boolean completionDeadlineEnabled;
  private LocalTime completionDeadlineLocalTime;
  private Integer completionDeadlineDayOffset;
  private String completionDeadlineSeverity;
  private Integer dependencyCompletionWindowSeconds;
  private String shardStrategy;

  /** ExecutionMode 枚举 code:FULL / INCREMENTAL / CDC,默认 FULL,见 V73 migration。 */
  private String executionMode;

  /** 增量模式下的水位字段名(例:update_time / id);FULL 模式下可空。 */
  private String watermarkField;

  private String executionHandler;
  private String paramSchema;
  private String defaultParams;
  private Integer priority;
  private Long version;
  private Long defaultWorkflowDefinitionId;
  private String description;
  private Boolean enabled;
  private String createdBy;
  private String updatedBy;
  private Instant createdAt;
  private Instant updatedAt;
}
