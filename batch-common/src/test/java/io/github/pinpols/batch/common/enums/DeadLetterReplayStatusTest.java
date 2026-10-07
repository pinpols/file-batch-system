package io.github.pinpols.batch.common.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DeadLetterReplayStatus 死信重放状态: 编码 / 展示名称声明与状态项数量锁定")
class DeadLetterReplayStatusTest {

  @Test
  @DisplayName("各死信重放状态的编码与约定值逐一对应")
  void shouldHaveCorrectCodeValues() {
    assertThat(DeadLetterReplayStatus.NEW.code()).isEqualTo("NEW");
    assertThat(DeadLetterReplayStatus.REPLAYING.code()).isEqualTo("REPLAYING");
    assertThat(DeadLetterReplayStatus.SUCCESS.code()).isEqualTo("SUCCESS");
    assertThat(DeadLetterReplayStatus.FAILED.code()).isEqualTo("FAILED");
    assertThat(DeadLetterReplayStatus.GIVE_UP.code()).isEqualTo("GIVE_UP");
  }

  @Test
  @DisplayName("每个死信重放状态都有非空的展示名称")
  void shouldHaveNonBlankLabels() {
    for (DeadLetterReplayStatus status : DeadLetterReplayStatus.values()) {
      assertThat(status.label()).as("label for %s", status.name()).isNotBlank();
    }
  }

  @Test
  @DisplayName("每个死信重放状态的编码与其枚举名保持一致")
  void shouldKeepCodeEqualToEnumName_whenEnumeratingAllReplayStatuses() {
    for (DeadLetterReplayStatus status : DeadLetterReplayStatus.values()) {
      assertThat(status.code()).isEqualTo(status.name());
    }
  }

  @Test
  @DisplayName("新进入与失败两类可作为重放入口的状态保持原有编码")
  void shouldHaveReplayableInitialStatuses() {
    // NEW 和 FAILED 是可重放的初始状态
    assertThat(DeadLetterReplayStatus.NEW.code()).isEqualTo("NEW");
    assertThat(DeadLetterReplayStatus.FAILED.code()).isEqualTo("FAILED");
  }

  @Test
  @DisplayName("死信重放状态共五项,新增或删除需同步调整")
  void shouldContainFiveValues() {
    assertThat(DeadLetterReplayStatus.values()).hasSize(5);
  }
}
