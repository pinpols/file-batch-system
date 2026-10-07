package io.github.pinpols.batch.common.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RetryScheduleStatus 重试计划状态: 编码 / 展示名称声明与状态项数量锁定")
class RetryScheduleStatusTest {

  @Test
  @DisplayName("各重试计划状态的编码与约定值逐一对应")
  void shouldHaveCorrectCodeValues() {
    assertThat(RetryScheduleStatus.WAITING.code()).isEqualTo("WAITING");
    assertThat(RetryScheduleStatus.RUNNING.code()).isEqualTo("RUNNING");
    assertThat(RetryScheduleStatus.SUCCESS.code()).isEqualTo("SUCCESS");
    assertThat(RetryScheduleStatus.FAILED.code()).isEqualTo("FAILED");
    assertThat(RetryScheduleStatus.EXHAUSTED.code()).isEqualTo("EXHAUSTED");
    assertThat(RetryScheduleStatus.CANCELLED.code()).isEqualTo("CANCELLED");
  }

  @Test
  @DisplayName("每个重试计划状态都有非空的展示名称")
  void shouldHaveNonBlankLabels() {
    for (RetryScheduleStatus status : RetryScheduleStatus.values()) {
      assertThat(status.label()).as("label for %s", status.name()).isNotBlank();
    }
  }

  @Test
  @DisplayName("每个重试计划状态的编码与其枚举名保持一致")
  void shouldKeepCodeEqualToEnumName_whenEnumeratingAllRetryScheduleStatuses() {
    for (RetryScheduleStatus status : RetryScheduleStatus.values()) {
      assertThat(status.code()).isEqualTo(status.name());
    }
  }

  @Test
  @DisplayName("重试计划状态共六项,新增或删除需显式调整")
  void shouldContainSixValues() {
    assertThat(RetryScheduleStatus.values()).hasSize(6);
  }
}
