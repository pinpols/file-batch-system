package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum OperationResult implements DictEnum {
  SUCCESS("SUCCESS", "成功"),
  FAILED("FAILED", "失败");

  private final String code;
  private final String label;
  public String label() { return label; }}
