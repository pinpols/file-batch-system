package io.github.pinpols.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum CalendarDayType implements DictEnum {
  HOLIDAY("HOLIDAY", "节假日"),
  WORKDAY_OVERRIDE("WORKDAY_OVERRIDE", "补班日");

  private final String code;
  private final String label;
}
