package io.github.pinpols.batch.trigger.support;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.util.Arrays;
import java.util.List;
import lombok.Data;

/**
 * 触发器描述符，承载调度器注册一个触发器所需的全部配置信息。 由 {@link
 * io.github.pinpols.batch.trigger.domain.TriggerDefinitionLoader} 从数据库加载， 并传递给 {@link
 * io.github.pinpols.batch.trigger.domain.TriggerRegistrationService} 完成注册； {@code enabled}
 * 字段决定该触发器是否参与全量注册（Workflow、Job 和单独的 schedule_enabled 均需启用），{@code scheduleExpression} 支持 CRON 和固定频率两种格式。
 */
@Data
public class TriggerDescriptor {

  /** job_definition.id，供定义加载和诊断使用。 */
  private Long jobDefinitionId;

  /** 标识到每个 Worker 任务，避免并行节点的结果依赖唯一活动 */
  private Long workflowDefinitionId;

  private String workflowCode;
  private Integer workflowVersion;
  /** job_definition 乐观锁版本，用于区分重配置前后的预期 fire。 */
  private Integer jobDefinitionVersion;

  private String tenantId;
  private String jobCode;
  private String scheduleType;
  private String scheduleExpression;
  private String timezone;
  private String triggerMode;
  private String calendarCode;
  private String catchUpPolicy;
  private Integer catchUpMaxDays;

  /** ADR-043:上游 job code(可空)。非空则 fire 前要求该 job 同 bizDate 已 SUCCESS,否则跳过本次。 */
  private String dependsOnJobCode;

  /** 节点能否从 workflow_run.current_node_code 推断出来。 */
  private List<String> dependsOnJobCodes;

  /** 创建分区或批量写入前，先验证每个 bundle 来源，避免单个 */
  public void setDependsOnJobCodesCsv(String value) {
    if (EmptyChecks.isNull(value)) {
      dependsOnJobCodes = null;
      return;
    }
    if (EmptyChecks.isBlank(value)) {
      dependsOnJobCodes = List.of();
      return;
    }
    String separator = String.valueOf((char) 31);
    dependsOnJobCodes = Arrays.stream(value.split(separator))
        .map(String::trim)
        .filter(code -> EmptyChecks.isNotEmpty(code))
        .toList();
  }

  private String dependsOnJobCodesCsv;

  private boolean enabled;

  private boolean workflowEnabled;
}
