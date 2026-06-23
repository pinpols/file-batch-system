package io.github.pinpols.batch.worker.core.domain;

import io.github.pinpols.batch.common.utils.Texts;
import java.util.Map;
import lombok.Builder;

/**
 * Pipeline 单个步骤的静态定义，从配置层加载后在整个执行周期内只读。 包含步骤标识、实现编码、参数映射、超时与重试策略等元信息； 提供 {@link #textParam} /
 * {@link #booleanParam} 工具方法， 屏蔽 stepParams Map 的类型转换细节，供 StepHandler 安全读取配置。
 */
@Builder
public record PipelineStepDefinition(
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

  public String textParam(String... keys) {
    if (stepParams == null || keys == null) {
      return null;
    }
    for (String key : keys) {
      Object value = stepParams.get(key);
      if (value instanceof String text && Texts.hasText(text)) {
        return text;
      }
      if (value != null) {
        String text = String.valueOf(value);
        if (Texts.hasText(text) && !"null".equalsIgnoreCase(text)) {
          return text;
        }
      }
    }
    return null;
  }

  public boolean booleanParam(String... keys) {
    if (stepParams == null || keys == null) {
      return false;
    }
    for (String key : keys) {
      Object value = stepParams.get(key);
      if (value instanceof Boolean bool) {
        return bool;
      }
      if (value != null) {
        String text = String.valueOf(value);
        if ("true".equalsIgnoreCase(text)) {
          return true;
        }
        if ("false".equalsIgnoreCase(text)) {
          return false;
        }
      }
    }
    return false;
  }
}
