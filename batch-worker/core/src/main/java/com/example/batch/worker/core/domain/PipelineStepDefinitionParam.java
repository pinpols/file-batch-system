package com.example.batch.worker.core.domain;

import java.util.Map;

/** 流水线步骤定义参数对象。 封装 PipelineStepDefinition 的构造参数，遵循编码规范（参数 ≥ 7 必须封装）。 */
public record PipelineStepDefinitionParam(
    Long id,
    Long pipelineDefinitionId,
    String stepCode,
    String stepName,
    String stageCode,
    Integer stepOrder,
    String implCode,
    Map<String, Object> stepParams,
    Integer timeoutSeconds,
    String retryPolicy,
    Integer retryMaxCount,
    boolean enabled) {
  /** 转换为 PipelineStepDefinition。 */
  public PipelineStepDefinition toDefinition() {
    return new PipelineStepDefinition(
        id,
        pipelineDefinitionId,
        stepCode,
        stepName,
        stageCode,
        stepOrder,
        implCode,
        stepParams,
        timeoutSeconds,
        retryPolicy,
        retryMaxCount,
        enabled);
  }

  /** 从 PipelineStepDefinition 创建参数对象。 */
  public static PipelineStepDefinitionParam fromDefinition(PipelineStepDefinition definition) {
    return new PipelineStepDefinitionParam(
        definition.id(),
        definition.pipelineDefinitionId(),
        definition.stepCode(),
        definition.stepName(),
        definition.stageCode(),
        definition.stepOrder(),
        definition.implCode(),
        definition.stepParams(),
        definition.timeoutSeconds(),
        definition.retryPolicy(),
        definition.retryMaxCount(),
        definition.enabled());
  }
}
