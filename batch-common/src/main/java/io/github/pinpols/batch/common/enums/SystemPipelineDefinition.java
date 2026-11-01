package io.github.pinpols.batch.common.enums;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.util.List;

/** 不可变的系统文件 Pipeline 契约；阶段顺序和语义版本随应用代码发布。 */
public record SystemPipelineDefinition(String code, int semanticVersion, List<String> stages) {

  public SystemPipelineDefinition {
    if (EmptyChecks.isBlank(code)) {
      throw new IllegalArgumentException("system pipeline code is required");
    }
    if (semanticVersion < 1) {
      throw new IllegalArgumentException("system pipeline semantic version must be positive");
    }
    stages = List.copyOf(stages);
    if (EmptyChecks.isEmpty(stages)
        || stages.stream()
            .anyMatch(stage -> EmptyChecks.isBlank(stage))) {
      throw new IllegalArgumentException("system pipeline stages must be non-empty");
    }
  }
}
