package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum WorkerRegistryStatus implements DictEnum {
  ONLINE("ONLINE", "在线"),
  OFFLINE("OFFLINE", "离线"),
  DRAINING("DRAINING", "排空中"),
  DECOMMISSIONED("DECOMMISSIONED", "已下线");

  private final String code;
  private final String label;
}
