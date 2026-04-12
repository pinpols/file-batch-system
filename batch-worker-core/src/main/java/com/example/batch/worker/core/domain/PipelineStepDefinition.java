package com.example.batch.worker.core.domain;

import java.util.Map;
import org.springframework.util.StringUtils;

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
      if (value instanceof String text && StringUtils.hasText(text)) {
        return text;
      }
      if (value != null) {
        String text = String.valueOf(value);
        if (StringUtils.hasText(text) && !"null".equalsIgnoreCase(text)) {
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
