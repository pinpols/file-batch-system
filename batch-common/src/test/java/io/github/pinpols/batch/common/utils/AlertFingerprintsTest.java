package io.github.pinpols.batch.common.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("告警指纹工具: 构建结果的确定性, 输入敏感度与固定长度十六进制形态")
class AlertFingerprintsTest {

  @Test
  @DisplayName("相同输入重复构建: 指纹完全一致")
  void shouldBeDeterministicForSameInputs() {
    String a = AlertFingerprints.build("t1", "SLA", "job:1");
    String b = AlertFingerprints.build("t1", "SLA", "job:1");
    assertThat(a).isEqualTo(b);
  }

  @Test
  @DisplayName("输入仅一处不同: 指纹随之变化, 可用于区分告警来源")
  void shouldDifferWhenInputsDiffer() {
    String a = AlertFingerprints.build("t1", "SLA", "job:1");
    String b = AlertFingerprints.build("t1", "SLA", "job:2");
    assertThat(a).isNotEqualTo(b);
  }

  @Test
  @DisplayName("空值参与构建: 仍输出 64 位小写十六进制")
  void shouldHaveFixedLength64Hex() {
    String fp = AlertFingerprints.build(null, "X", null);
    assertThat(fp).hasSize(64).matches("[0-9a-f]+");
  }
}
