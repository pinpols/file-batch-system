package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum WorkflowNodeType implements DictEnum {
  TASK("TASK", "任务节点"),
  GATEWAY("GATEWAY", "网关节点"),
  FILE_STEP("FILE_STEP", "文件步骤"),
  START("START", "开始节点"),
  END("END", "结束节点"),
  JOB("JOB", "作业节点"),
  // ADR-028: Sensor 等待外部条件（FILE_ARRIVAL / HTTP_POLL / KAFKA_OFFSET / DB_ROW_EXISTS）
  WAIT("WAIT", "等待节点");

  private final String code;
  private final String label;
}
