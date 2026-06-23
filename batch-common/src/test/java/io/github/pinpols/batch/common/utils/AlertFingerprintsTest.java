package io.github.pinpols.batch.common.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AlertFingerprintsTest {

  @Test
  void shouldBeDeterministicForSameInputs() {
    String a = AlertFingerprints.build("t1", "SLA", "job:1");
    String b = AlertFingerprints.build("t1", "SLA", "job:1");
    assertThat(a).isEqualTo(b);
  }

  @Test
  void shouldDifferWhenInputsDiffer() {
    String a = AlertFingerprints.build("t1", "SLA", "job:1");
    String b = AlertFingerprints.build("t1", "SLA", "job:2");
    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void shouldHaveFixedLength64Hex() {
    String fp = AlertFingerprints.build(null, "X", null);
    assertThat(fp).hasSize(64).matches("[0-9a-f]+");
  }
}
