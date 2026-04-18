package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum AlertStatus implements DictEnum {
  OPEN("OPEN", "待处理"),
  ACKED("ACKED", "已确认"),
  SUPPRESSED("SUPPRESSED", "已抑制"),
  CLOSED("CLOSED", "已关闭");

  private final String code;
  private final String label;
  public String label() { return label; }}
