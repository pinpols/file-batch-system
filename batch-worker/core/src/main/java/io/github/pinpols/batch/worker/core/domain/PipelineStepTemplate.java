package io.github.pinpols.batch.worker.core.domain;

import java.util.Map;
import lombok.Builder;

@Builder
public record PipelineStepTemplate(
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
  /** 从参数对象创建 PipelineStepTemplate。 */
  public static PipelineStepTemplate from(PipelineStepTemplateParam param) {
    return PipelineStepTemplate.builder()
        .stepCode(param.stepCode())
        .stepName(param.stepName())
        .stageCode(param.stageCode())
        .stepOrder(param.stepOrder())
        .implCode(param.implCode())
        .stepParams(param.stepParams())
        .timeoutSeconds(param.timeoutSeconds())
        .retryPolicy(param.retryPolicy())
        .retryMaxCount(param.retryMaxCount())
        .enabled(param.enabled())
        .build();
  }
}
