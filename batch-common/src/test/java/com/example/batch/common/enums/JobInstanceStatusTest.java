package com.example.batch.common.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JobInstanceStatusTest {

  @Test
  void shouldHaveCorrectCodeValues() {
    assertThat(JobInstanceStatus.CREATED.code()).isEqualTo("CREATED");
    assertThat(JobInstanceStatus.WAITING.code()).isEqualTo("WAITING");
    assertThat(JobInstanceStatus.READY.code()).isEqualTo("READY");
    assertThat(JobInstanceStatus.RUNNING.code()).isEqualTo("RUNNING");
    assertThat(JobInstanceStatus.PARTIAL_FAILED.code()).isEqualTo("PARTIAL_FAILED");
    assertThat(JobInstanceStatus.SUCCESS.code()).isEqualTo("SUCCESS");
    assertThat(JobInstanceStatus.FAILED.code()).isEqualTo("FAILED");
    assertThat(JobInstanceStatus.CANCELLED.code()).isEqualTo("CANCELLED");
    assertThat(JobInstanceStatus.TERMINATED.code()).isEqualTo("TERMINATED");
  }

  @Test
  void shouldHaveNonBlankLabels() {
    for (JobInstanceStatus status : JobInstanceStatus.values()) {
      assertThat(status.label()).as("label for %s", status.name()).isNotBlank();
    }
  }

  @Test
  void codeShouldMatchEnumName() {
    for (JobInstanceStatus status : JobInstanceStatus.values()) {
      assertThat(status.code()).as("code for %s", status.name()).isEqualTo(status.name());
    }
  }

  @Test
  void shouldContainElevenValues() {
    // 9 业务终态 + ADR-026 dry-run 2 个终态 (SUCCESS_DRY_RUN / FAILED_DRY_RUN)
    assertThat(JobInstanceStatus.values()).hasSize(11);
  }
}
