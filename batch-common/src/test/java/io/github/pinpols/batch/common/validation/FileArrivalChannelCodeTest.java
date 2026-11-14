package io.github.pinpols.batch.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("文件到达渠道编码:空值放行、长度上限 128 与非字符串拒绝")
class FileArrivalChannelCodeTest {

  @Test
  @DisplayName("空值视为未配置渠道,直接通过")
  void accepts_null() {
    assertThat(FileArrivalChannelCode.isValid(null)).isTrue();
  }

  @Test
  @DisplayName("非字符串类型不接受")
  void rejects_non_string() {
    assertThat(FileArrivalChannelCode.isValid(128)).isFalse();
  }

  @Test
  @DisplayName("长度 128 的编码通过,129 被拒绝")
  void enforces_channel_code_length_limit() {
    assertThat(FileArrivalChannelCode.isValid("c".repeat(128))).isTrue();
    assertThat(FileArrivalChannelCode.isValid("c".repeat(129))).isFalse();
  }
}
