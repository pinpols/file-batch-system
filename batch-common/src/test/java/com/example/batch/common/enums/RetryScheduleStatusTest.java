package com.example.batch.common.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RetryScheduleStatusTest {

  @Test
  void shouldHaveCorrectCodeValues() {
    assertThat(RetryScheduleStatus.WAITING.code()).isEqualTo("WAITING");
    assertThat(RetryScheduleStatus.RUNNING.code()).isEqualTo("RUNNING");
    assertThat(RetryScheduleStatus.SUCCESS.code()).isEqualTo("SUCCESS");
    assertThat(RetryScheduleStatus.FAILED.code()).isEqualTo("FAILED");
    assertThat(RetryScheduleStatus.EXHAUSTED.code()).isEqualTo("EXHAUSTED");
    assertThat(RetryScheduleStatus.CANCELLED.code()).isEqualTo("CANCELLED");
  }

  @Test
  void shouldHaveNonBlankLabels() {
    for (RetryScheduleStatus status : RetryScheduleStatus.values()) {
      assertThat(status.label()).as("label for %s", status.name()).isNotBlank();
    }
  }

  @Test
  void codeShouldMatchEnumName() {
    for (RetryScheduleStatus status : RetryScheduleStatus.values()) {
      assertThat(status.code()).isEqualTo(status.name());
    }
  }

  @Test
  void shouldContainSixValues() {
    assertThat(RetryScheduleStatus.values()).hasSize(6);
  }
}
