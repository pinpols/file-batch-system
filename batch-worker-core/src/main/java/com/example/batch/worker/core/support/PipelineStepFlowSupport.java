package com.example.batch.worker.core.support;

import com.example.batch.worker.core.domain.PipelineStepDefinition;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

public final class PipelineStepFlowSupport {

  private PipelineStepFlowSupport() {}

  public static PipelineStepDefinition firstStep(List<PipelineStepDefinition> steps) {
    return steps == null || steps.isEmpty() ? null : steps.get(0);
  }

  public static int maxTransitionGuard(List<PipelineStepDefinition> steps) {
    return Math.max((steps == null ? 0 : steps.size()) * 4, 16);
  }

  public static PipelineStepDefinition resolveNextStep(
      PipelineStepDefinition current,
      boolean success,
      List<PipelineStepDefinition> steps,
      Map<String, Object> attributes) {
    if (current != null && success && current.booleanParam("terminalOnSuccess", "stopOnSuccess")) {
      return null;
    }
    if (current != null && !success && current.booleanParam("terminalOnFailure", "stopOnFailure")) {
      return null;
    }
    String selector = popSelector(attributes);
    if (!StringUtils.hasText(selector) && current != null) {
      selector =
          success
              ? current.textParam(
                  "onSuccessNextStepCode",
                  "onSuccessNextStageCode",
                  "nextStepCode",
                  "nextStageCode")
              : current.textParam("onFailureNextStepCode", "onFailureNextStageCode");
    }
    if (StringUtils.hasText(selector)) {
      PipelineStepDefinition explicitNext = findByStepCodeOrStageCode(steps, selector);
      if (explicitNext != null) {
        return explicitNext;
      }
    }
    if (!success || current == null || steps == null || steps.isEmpty()) {
      return null;
    }
    for (int index = 0; index < steps.size(); index++) {
      if (steps.get(index).stepCode().equals(current.stepCode())) {
        return index + 1 < steps.size() ? steps.get(index + 1) : null;
      }
    }
    return null;
  }

  private static String popSelector(Map<String, Object> attributes) {
    String stepCode = popText(attributes, PipelineRuntimeKeys.PIPELINE_NEXT_STEP_CODE);
    return StringUtils.hasText(stepCode)
        ? stepCode
        : popText(attributes, PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE);
  }

  private static String popText(Map<String, Object> attributes, String key) {
    if (attributes == null || !attributes.containsKey(key)) {
      return null;
    }
    Object value = attributes.remove(key);
    if (value instanceof String text && StringUtils.hasText(text)) {
      return text;
    }
    if (value != null) {
      String text = String.valueOf(value);
      if (StringUtils.hasText(text) && !"null".equalsIgnoreCase(text)) {
        return text;
      }
    }
    return null;
  }

  private static PipelineStepDefinition findByStepCodeOrStageCode(
      List<PipelineStepDefinition> steps, String selector) {
    if (steps == null || !StringUtils.hasText(selector)) {
      return null;
    }
    for (PipelineStepDefinition step : steps) {
      if (selector.equalsIgnoreCase(step.stepCode())
          || selector.equalsIgnoreCase(step.stageCode())) {
        return step;
      }
    }
    return null;
  }
}
