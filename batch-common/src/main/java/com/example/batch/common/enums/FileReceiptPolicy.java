package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum FileReceiptPolicy implements DictEnum {
  NONE("NONE", "无回执"),
  SYNC("SYNC", "同步"),
  ASYNC("ASYNC", "异步"),
  POLLING("POLLING", "轮询");

  private final String code;
  private final String label;
}
