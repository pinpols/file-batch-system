package io.github.pinpols.batch.common.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("文件完整性状态:持久化编码与内部展示标签")
class FileIntegrityStateTest {

  @Test
  @DisplayName("已验证状态提供稳定编码与标签并符合字典契约")
  void shouldExposeVerifiedCodeAndLabel() {
    DictEnum state = FileIntegrityState.VERIFIED;

    assertThat(state.code()).isEqualTo("VERIFIED");
    assertThat(state.label()).isEqualTo("已验证");
  }
}
