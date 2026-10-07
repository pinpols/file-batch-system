package io.github.pinpols.batch.common.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TaskStatus 任务状态: 编码 / 展示名称声明与状态项数量锁定")
class TaskStatusTest {

  @Test
  @DisplayName("各任务状态的编码与约定值逐一对应")
  void shouldHaveCorrectCodeValues() {
    assertThat(TaskStatus.CREATED.code()).isEqualTo("CREATED");
    assertThat(TaskStatus.READY.code()).isEqualTo("READY");
    assertThat(TaskStatus.RUNNING.code()).isEqualTo("RUNNING");
    assertThat(TaskStatus.SUCCESS.code()).isEqualTo("SUCCESS");
    assertThat(TaskStatus.FAILED.code()).isEqualTo("FAILED");
    assertThat(TaskStatus.CANCELLED.code()).isEqualTo("CANCELLED");
    assertThat(TaskStatus.TERMINATED.code()).isEqualTo("TERMINATED");
  }

  @Test
  @DisplayName("每个任务状态都有非空的展示名称")
  void shouldHaveNonBlankLabels() {
    for (TaskStatus status : TaskStatus.values()) {
      assertThat(status.label()).as("label for %s", status.name()).isNotBlank();
    }
  }

  @Test
  @DisplayName("每个任务状态的编码与其枚举名保持一致")
  void shouldKeepCodeEqualToEnumName_whenEnumeratingAllTaskStatuses() {
    for (TaskStatus status : TaskStatus.values()) {
      assertThat(status.code()).isEqualTo(status.name());
    }
  }

  @Test
  @DisplayName("任务状态共七项,新增或删除需显式调整")
  void shouldContainSevenValues() {
    assertThat(TaskStatus.values()).hasSize(7);
  }
}
