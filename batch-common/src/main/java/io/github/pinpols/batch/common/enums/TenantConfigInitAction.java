package io.github.pinpols.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum TenantConfigInitAction implements DictEnum {
  CREATED("CREATED", "新建"),
  UPDATED("UPDATED", "更新"),
  SKIPPED("SKIPPED", "跳过"),
  FAILED("FAILED", "失败");

  private final String code;
  private final String label;
}
