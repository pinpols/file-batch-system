package io.github.pinpols.batch.common.enums;

import java.util.List;

import io.github.pinpols.batch.common.utils.EmptyValues;

/** 不可变的系统文件 Pipeline 契约；阶段顺序和语义版本随应用代码发布。 */
public record SystemPipelineDefinition(String code, int semanticVersion, List<String> stages) {

    public SystemPipelineDefinition {
        if (EmptyValues.isBlank(code)) {
            throw new IllegalArgumentException("system pipeline code is required");
        }
        if (semanticVersion < 1) {
            throw new IllegalArgumentException("system pipeline semantic version must be positive");
        }
        stages = List.copyOf(stages);
        if (EmptyValues.isEmpty(stages) || stages.stream().anyMatch(stage -> EmptyValues.isBlank(stage))) {
            throw new IllegalArgumentException("system pipeline stages must be non-empty");
        }
    }
}
