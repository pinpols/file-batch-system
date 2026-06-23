package io.github.pinpols.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum AiPromptCategory implements DictEnum {
  PLATFORM("PLATFORM", "平台咨询"),
  FILE_GOVERNANCE("FILE_GOVERNANCE", "文件治理"),
  WORKFLOW("WORKFLOW", "工作流"),
  OPERATIONS("OPERATIONS", "运维操作"),
  OUT_OF_SCOPE("OUT_OF_SCOPE", "超出范围");

  private final String code;
  private final String label;
}
