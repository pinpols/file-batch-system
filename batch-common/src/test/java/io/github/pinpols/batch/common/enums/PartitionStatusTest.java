package io.github.pinpols.batch.common.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PartitionStatus 分区状态: 编码 / 展示名称声明与状态项数量锁定")
class PartitionStatusTest {

  @Test
  @DisplayName("各分区状态的编码与约定值逐一对应")
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
  @DisplayName("每个分区状态都有非空的展示名称")
  void shouldHaveNonBlankLabels() {
    for (PartitionStatus status : PartitionStatus.values()) {
      assertThat(status.label()).as("label for %s", status.name()).isNotBlank();
    }
  }

  @Test
  @DisplayName("每个分区状态的编码与其枚举名保持一致")
  void shouldKeepCodeEqualToEnumName_whenEnumeratingAllPartitionStatuses() {
    for (PartitionStatus status : PartitionStatus.values()) {
      assertThat(status.code()).isEqualTo(status.name());
    }
  }

  @Test
  @DisplayName("分区状态共九项,新增或删除需显式调整")
  void shouldContainNineValues() {
    assertThat(PartitionStatus.values()).hasSize(9);
  }
}
