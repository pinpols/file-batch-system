package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum TriggerResourceType implements DictEnum {
  JOB("JOB", "作业"),
  WORKFLOW("WORKFLOW", "工作流"),
  FILE_CHANNEL("FILE_CHANNEL", "文件通道"),
  FILE_TEMPLATE("FILE_TEMPLATE", "文件模板");

  private final String code;
  private final String label;
}
