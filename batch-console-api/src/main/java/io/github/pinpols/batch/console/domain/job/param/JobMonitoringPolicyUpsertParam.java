package io.github.pinpols.batch.console.domain.job.param;

import java.time.LocalTime;
import lombok.Builder;
import lombok.Getter;

/** 作业监控策略写入参数，保持 Mapper 的持久化契约以具名字段表达。 */
@Getter
@Builder
public class JobMonitoringPolicyUpsertParam {

  private final String tenantId;
  private final Long jobDefinitionId;
  private final Integer softRuntimeSeconds;
  private final String softRuntimeSeverity;
  private final Integer startGraceSeconds;
  private final String startGraceSeverity;
  private final LocalTime completionDeadlineLocalTime;
  private final Integer completionDeadlineDayOffset;
  private final Integer dependencyCompletionWindowSeconds;
  private final String completionDeadlineSeverity;
  private final String updatedBy;
}
