package io.github.pinpols.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum WorkflowDefinitionStatus implements DictEnum {
  DRAFT("DRAFT", "草稿"),
  PUBLISHED("PUBLISHED", "已发布"),
  DISABLED("DISABLED", "已停用");

  private final String code;
  private final String label;
}
