package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum ApprovalType implements DictEnum {
  CATCH_UP("CATCH_UP", "补跑"),
  COMPENSATION("COMPENSATION", "补偿"),
  DLQ_REPLAY("DLQ_REPLAY", "死信重放"),
  DOWNLOAD("DOWNLOAD", "下载");

  private final String code;
  private final String label;
}
