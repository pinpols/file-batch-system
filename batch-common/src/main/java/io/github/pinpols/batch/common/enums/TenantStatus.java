package io.github.pinpols.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum TenantStatus implements DictEnum {
  ACTIVE("ACTIVE", "正常"),
  SUSPENDED("SUSPENDED", "已暂停");

  private final String code;
  private final String label;
}
