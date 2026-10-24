package io.github.pinpols.batch.orchestrator.application.plan;

import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import java.util.Map;

/** 计划输入；执行路径传入已选定的定义，配置预览可按编码读取当前定义。 */
public record SchedulePlanCommand(
    String tenantId,
    String jobCode,
    String bizDate,
    Map<String, Object> params,
    JobDefinitionEntity executionDefinition) {

  public SchedulePlanCommand(
      String tenantId, String jobCode, String bizDate, Map<String, Object> params) {
    this(tenantId, jobCode, bizDate, params, null);
  }
}
