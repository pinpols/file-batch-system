package io.github.pinpols.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum ConfigLifecycleStatus implements DictEnum {
  DRAFT("DRAFT", "草稿"),
  PENDING_APPROVAL("PENDING_APPROVAL", "待审批"),
  PUBLISHED("PUBLISHED", "已发布"),
  GRAY("GRAY", "灰度"),
  ROLLED_BACK("ROLLED_BACK", "已回滚");

  private final String code;
  private final String label;
}
