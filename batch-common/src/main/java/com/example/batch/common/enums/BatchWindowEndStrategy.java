package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum BatchWindowEndStrategy implements DictEnum {
  STOP("STOP", "立即停止"),
  FINISH_RUNNING("FINISH_RUNNING", "等待运行中任务完成"),
  CONTINUE("CONTINUE", "继续运行");

  private final String code;
  private final String label;
}
