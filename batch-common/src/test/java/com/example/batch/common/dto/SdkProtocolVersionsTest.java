package com.example.batch.common.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link SdkProtocolVersions} 解析 + 支持判定单元测试。 */
class SdkProtocolVersionsTest {

  @Test
  @DisplayName("normalizeMajor: 容忍 v 前缀 / 小数尾 / rc 后缀,归一为 v<major>")
  void normalizeMajorTolerant() {
    assertThat(SdkProtocolVersions.normalizeMajor("v1")).isEqualTo("v1");
    assertThat(SdkProtocolVersions.normalizeMajor("V2")).isEqualTo("v2");
    assertThat(SdkProtocolVersions.normalizeMajor("v2-rc")).isEqualTo("v2");
    assertThat(SdkProtocolVersions.normalizeMajor("1.3.0")).isEqualTo("v1");
    assertThat(SdkProtocolVersions.normalizeMajor(" v3 ")).isEqualTo("v3");
  }

  @Test
  @DisplayName("normalizeMajor: 空 / 无前导数字 → null")
  void normalizeMajorUnparseable() {
    assertThat(SdkProtocolVersions.normalizeMajor(null)).isNull();
    assertThat(SdkProtocolVersions.normalizeMajor("")).isNull();
    assertThat(SdkProtocolVersions.normalizeMajor("  ")).isNull();
    assertThat(SdkProtocolVersions.normalizeMajor("abc")).isNull();
    assertThat(SdkProtocolVersions.normalizeMajor("v")).isNull();
  }

  @Test
  @DisplayName("isSupportedMajor: v1/v2 支持;v3 / 无法解析 / null 不支持")
  void isSupportedMajor() {
    assertThat(SdkProtocolVersions.isSupportedMajor("v1")).isTrue();
    assertThat(SdkProtocolVersions.isSupportedMajor("v2")).isTrue();
    assertThat(SdkProtocolVersions.isSupportedMajor("1.0.0")).isTrue();
    assertThat(SdkProtocolVersions.isSupportedMajor("v3")).isFalse();
    assertThat(SdkProtocolVersions.isSupportedMajor("v10")).isFalse();
    assertThat(SdkProtocolVersions.isSupportedMajor("abc")).isFalse();
    assertThat(SdkProtocolVersions.isSupportedMajor(null)).isFalse();
  }
}
