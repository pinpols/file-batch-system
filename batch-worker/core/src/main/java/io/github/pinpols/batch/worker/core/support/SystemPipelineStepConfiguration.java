package io.github.pinpols.batch.worker.core.support;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.worker.core.domain.PipelineStepDefinition;
import io.github.pinpols.batch.worker.core.domain.PipelineStepTemplate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 为 JOB_RERUN 根 Workflow 启动选择不可变的 Job 定义快照。 */
final class SystemPipelineStepConfiguration {

  private static final Set<String> CONTROL_FLOW_PARAMS = Set.of(
      "terminalOnSuccess",
      "stopOnSuccess",
      "onSuccessNextStepCode",
      "onSuccessNextStageCode",
      "nextStepCode",
      "nextStageCode",
      "onFailureNextStepCode",
      "onFailureNextStageCode");

  private SystemPipelineStepConfiguration() {}

  static List<PipelineStepDefinition> apply(
      List<PipelineStepTemplate> templates, Map<String, Object> paramsByStage) {
    Set<String> knownStages = templates.stream()
        .map(PipelineStepTemplate::stageCode)
        .collect(Collectors.toUnmodifiableSet());
    for (String stageCode : paramsByStage.keySet()) {
      if (!knownStages.contains(stageCode)) {
        throw new IllegalArgumentException("unknown system Pipeline stage: " + stageCode);
      }
      if (!(paramsByStage.get(stageCode) instanceof Map<?, ?>)) {
        throw new IllegalArgumentException(
            "system Pipeline stage parameters must be an object: " + stageCode);
      }
    }

    List<PipelineStepDefinition> steps = new ArrayList<>(templates.size());
    for (PipelineStepTemplate template : templates) {
      Map<String, Object> effectiveParams = new LinkedHashMap<>();
      if (EmptyChecks.isNotNull(template.stepParams())) {
        effectiveParams.putAll(template.stepParams());
      }
      Object stageConfig = paramsByStage.get(template.stageCode());
      if (stageConfig instanceof Map<?, ?> configured) {
        for (Map.Entry<?, ?> entry : configured.entrySet()) {
          String key = String.valueOf(entry.getKey());
          if (CONTROL_FLOW_PARAMS.contains(key)) {
            throw new IllegalArgumentException(
                "Job parameters cannot override system Pipeline control flow: " + key);
          }
          effectiveParams.put(key, entry.getValue());
        }
      }
      PipelineStepDefinition step = PipelineStepDefinition.builder()
          .stepCode(template.stepCode())
          .stepName(template.stepName())
          .stageCode(template.stageCode())
          .stepOrder(template.stepOrder())
          .implCode(template.implCode())
          .stepParams(Collections.unmodifiableMap(effectiveParams))
          .timeoutSeconds(template.timeoutSeconds())
          .retryPolicy(template.retryPolicy())
          .retryMaxCount(template.retryMaxCount())
          .enabled(template.enabled())
          .build();
      steps.add(step);
    }
    return List.copyOf(steps);
  }
}
