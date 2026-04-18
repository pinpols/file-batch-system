package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum QueuePriorityPolicy implements DictEnum {
  FIFO("FIFO", "先进先出"),
  PRIORITY("PRIORITY", "优先级"),
  FAIR_SHARE("FAIR_SHARE", "公平共享");

  private final String code;
  private final String label;
}
