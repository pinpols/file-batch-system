package io.github.pinpols.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum FileReceiptStatus implements DictEnum {
  NONE("NONE", "无回执"),
  PENDING("PENDING", "待确认"),
  SUCCESS("SUCCESS", "成功"),
  FAILED("FAILED", "失败");

  private final String code;
  private final String label;
}
