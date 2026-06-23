package io.github.pinpols.batch.common.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PartitionStatusTest {

  @Test
  void shouldHaveCorrectCodeValues() {
    assertThat(PartitionStatus.CREATED.code()).isEqualTo("CREATED");
    assertThat(PartitionStatus.WAITING.code()).isEqualTo("WAITING");
    assertThat(PartitionStatus.READY.code()).isEqualTo("READY");
    assertThat(PartitionStatus.RUNNING.code()).isEqualTo("RUNNING");
    assertThat(PartitionStatus.SUCCESS.code()).isEqualTo("SUCCESS");
    assertThat(PartitionStatus.FAILED.code()).isEqualTo("FAILED");
    assertThat(PartitionStatus.RETRYING.code()).isEqualTo("RETRYING");
    assertThat(PartitionStatus.CANCELLED.code()).isEqualTo("CANCELLED");
    assertThat(PartitionStatus.TERMINATED.code()).isEqualTo("TERMINATED");
  }

  @Test
  void shouldHaveNonBlankLabels() {
    for (PartitionStatus status : PartitionStatus.values()) {
      assertThat(status.label()).as("label for %s", status.name()).isNotBlank();
    }
  }

  @Test
  void codeShouldMatchEnumName() {
    for (PartitionStatus status : PartitionStatus.values()) {
      assertThat(status.code()).isEqualTo(status.name());
    }
  }

  @Test
  void shouldContainNineValues() {
    assertThat(PartitionStatus.values()).hasSize(9);
  }
}
