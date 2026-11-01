package io.github.pinpols.batch.console.domain.job.view;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.domain.job.entity.JobDefinitionEntity;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 作业定义与依赖、托管 Workflow 和监控策略的查询投影。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class JobDefinitionView extends JobDefinitionEntity {
  private static final String DEPENDENCY_CODE_DELIMITER = String.valueOf((char) 31);

  private List<String> dependsOnJobCodes;
  private String dependsOnJobCodesCsv;
  private List<JobDependencyRuleView> jobDependencyRules = List.of();
  private Long defaultWorkflowDefinitionId;
  private Integer softRuntimeSeconds;
  private String softRuntimeSeverity;
  private Integer startGraceSeconds;
  private String startGraceSeverity;
  private Boolean completionDeadlineEnabled;
  private LocalTime completionDeadlineLocalTime;
  private Integer completionDeadlineDayOffset;
  private String completionDeadlineSeverity;
  private Integer dependencyCompletionWindowSeconds;

  public void setDependencyRulesJson(String value) {
    jobDependencyRules = EmptyChecks.isBlank(value)
        ? List.of()
        : JsonUtils.fromJson(value, new TypeReference<List<JobDependencyRuleView>>() {});
  }

  public void setDependsOnJobCodesCsv(String value) {
    dependsOnJobCodesCsv = value;
    dependsOnJobCodes = EmptyChecks.isBlank(value)
        ? List.of()
        : Arrays.stream(value.split(DEPENDENCY_CODE_DELIMITER))
            .map(String::trim)
            .filter(code -> EmptyChecks.isNotEmpty(code))
            .toList();
  }
}
