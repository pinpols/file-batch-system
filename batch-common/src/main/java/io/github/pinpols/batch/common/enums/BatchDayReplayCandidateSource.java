package io.github.pinpols.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/** 批量日演练候选的来源。 */
@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum BatchDayReplayCandidateSource implements DictEnum {
  EXISTING_INSTANCES("EXISTING_INSTANCES", "已有实例"),
  SCHEDULE_PLAN("SCHEDULE_PLAN", "调度计划");

  private final String code;
  private final String label;

  public static BatchDayReplayCandidateSource fromCodeOrNull(String value) {
    return DictEnum.fromCode(BatchDayReplayCandidateSource.class, value);
  }
}
