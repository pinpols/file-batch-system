package io.github.pinpols.batch.common.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.exception.BizException;
import org.junit.jupiter.api.Test;

class CatchUpPolicyTypeTest {

  @Test
  void fromCode_knownCodes_returnsCorrectValue() {
    assertThat(CatchUpPolicyType.fromCode("NONE")).isEqualTo(CatchUpPolicyType.NONE);
    assertThat(CatchUpPolicyType.fromCode("AUTO")).isEqualTo(CatchUpPolicyType.AUTO);
    assertThat(CatchUpPolicyType.fromCode("MANUAL_APPROVAL"))
        .isEqualTo(CatchUpPolicyType.MANUAL_APPROVAL);
  }

  @Test
  void fromCode_caseInsensitive() {
    assertThat(CatchUpPolicyType.fromCode("auto")).isEqualTo(CatchUpPolicyType.AUTO);
    assertThat(CatchUpPolicyType.fromCode("Manual_Approval"))
        .isEqualTo(CatchUpPolicyType.MANUAL_APPROVAL);
  }

  @Test
  void fromCode_nullOrBlank_returnsNone() {
    assertThat(CatchUpPolicyType.fromCode(null)).isEqualTo(CatchUpPolicyType.NONE);
    assertThat(CatchUpPolicyType.fromCode("")).isEqualTo(CatchUpPolicyType.NONE);
    assertThat(CatchUpPolicyType.fromCode("  ")).isEqualTo(CatchUpPolicyType.NONE);
  }

  @Test
  void fromCode_unknownCode_throwsBizException() {
    assertThatThrownBy(() -> CatchUpPolicyType.fromCode("UNKNOWN"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("unknown_catch_up_policy_type_code");
  }

  @Test
  void fromCodeOrDefault_unknownCode_returnsNone() {
    assertThat(CatchUpPolicyType.fromCodeOrDefault("GARBAGE")).isEqualTo(CatchUpPolicyType.NONE);
  }

  @Test
  void fromCodeOrDefault_nullOrBlank_returnsNone() {
    assertThat(CatchUpPolicyType.fromCodeOrDefault(null)).isEqualTo(CatchUpPolicyType.NONE);
    assertThat(CatchUpPolicyType.fromCodeOrDefault("")).isEqualTo(CatchUpPolicyType.NONE);
  }

  @Test
  void codeAndLabel_notBlank() {
    for (CatchUpPolicyType type : CatchUpPolicyType.values()) {
      assertThat(type.code()).isNotBlank();
      assertThat(type.label()).isNotBlank();
    }
  }
}
