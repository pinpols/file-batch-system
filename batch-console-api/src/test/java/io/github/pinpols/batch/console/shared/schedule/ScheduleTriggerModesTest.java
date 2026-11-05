package io.github.pinpols.batch.console.shared.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("调度类型与触发模式兼容规则")
class ScheduleTriggerModesTest {

  @ParameterizedTest(name = "{0} + {1} -> 兼容={2}")
  @CsvSource({
    "CRON, SCHEDULED, true",
    "CRON, MIXED, true",
    "CRON, MANUAL, false",
    "FIXED_RATE, MANUAL, false",
    "EVENT, EVENT, true",
    "EVENT, SCHEDULED, false",
    "MANUAL, MANUAL, true",
    "MANUAL, API, true",
    "MANUAL, SCHEDULED, false",
  })
  @DisplayName("与 Workflow 调度保存校验同一张兼容表")
  void shouldMatchWorkflowScheduleCompatibilityTable(
      String scheduleType, String triggerMode, boolean compatible) {
    assertThat(ScheduleTriggerModes.isCompatible(scheduleType, triggerMode)).isEqualTo(compatible);
  }

  @ParameterizedTest(name = "{0} + {1} -> {2}")
  @CsvSource({
    "CRON, MANUAL, SCHEDULED",
    "CRON, MIXED, MIXED",
    "FIXED_RATE, , SCHEDULED",
    "EVENT, MANUAL, EVENT",
    "MANUAL, , MANUAL",
    "MANUAL, API, API",
  })
  @DisplayName("兼容保留原值，空值或不兼容取调度类型默认值")
  void shouldResolveToCompatibleTriggerMode(
      String scheduleType, String triggerMode, String expected) {
    assertThat(ScheduleTriggerModes.resolve(scheduleType, triggerMode)).isEqualTo(expected);
  }

  @Test
  @DisplayName("调度类型缺省按手动处理")
  void shouldTreatMissingScheduleTypeAsManual() {
    assertThat(ScheduleTriggerModes.defaultFor(null)).isEqualTo("MANUAL");
    assertThat(ScheduleTriggerModes.isCompatible(null, "SCHEDULED")).isFalse();
  }
}
