package io.github.pinpols.batch.common.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ConfigLifecycleStatus 配置生命周期: 编码 / 展示名称声明与状态项数量锁定")
class ConfigLifecycleStatusTest {

  @Test
  @DisplayName("各配置生命周期状态的编码与约定值逐一对应")
  void shouldHaveExpectedCodeValues() {
    assertThat(ConfigLifecycleStatus.DRAFT.code()).isEqualTo("DRAFT");
    assertThat(ConfigLifecycleStatus.PENDING_APPROVAL.code()).isEqualTo("PENDING_APPROVAL");
    assertThat(ConfigLifecycleStatus.PUBLISHED.code()).isEqualTo("PUBLISHED");
    assertThat(ConfigLifecycleStatus.GRAY.code()).isEqualTo("GRAY");
    assertThat(ConfigLifecycleStatus.ROLLED_BACK.code()).isEqualTo("ROLLED_BACK");
  }

  @Test
  @DisplayName("每个配置生命周期状态都有非空的展示名称")
  void shouldHaveNonBlankLabels() {
    for (ConfigLifecycleStatus status : ConfigLifecycleStatus.values()) {
      assertThat(status.label()).as("label for %s", status.name()).isNotBlank();
    }
  }

  @Test
  @DisplayName("配置生命周期仅保留五个状态,新增或删除需同步调整")
  void shouldContainFiveValues() {
    assertThat(ConfigLifecycleStatus.values()).hasSize(5);
  }
}
