package io.github.pinpols.batch.common.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CatchUpPolicyType 补跑策略: 编码解析的大小写容忍 / 缺省回退与非法编码报错")
class CatchUpPolicyTypeTest {

  @Test
  @DisplayName("已知补跑策略编码解析为对应策略项")
  void fromCode_knownCodes_returnsCorrectValue() {
    assertThat(CatchUpPolicyType.fromCode("NONE")).isEqualTo(CatchUpPolicyType.NONE);
    assertThat(CatchUpPolicyType.fromCode("AUTO")).isEqualTo(CatchUpPolicyType.AUTO);
    assertThat(CatchUpPolicyType.fromCode("MANUAL_APPROVAL"))
        .isEqualTo(CatchUpPolicyType.MANUAL_APPROVAL);
  }

  @Test
  @DisplayName("编码解析忽略大小写,混合大小写同样命中")
  void fromCode_caseInsensitive() {
    assertThat(CatchUpPolicyType.fromCode("auto")).isEqualTo(CatchUpPolicyType.AUTO);
    assertThat(CatchUpPolicyType.fromCode("Manual_Approval"))
        .isEqualTo(CatchUpPolicyType.MANUAL_APPROVAL);
  }

  @Test
  @DisplayName("空值 / 空串 / 纯空白 解析回退为不补跑")
  void fromCode_nullOrBlank_returnsNone() {
    assertThat(CatchUpPolicyType.fromCode(null)).isEqualTo(CatchUpPolicyType.NONE);
    assertThat(CatchUpPolicyType.fromCode("")).isEqualTo(CatchUpPolicyType.NONE);
    assertThat(CatchUpPolicyType.fromCode("  ")).isEqualTo(CatchUpPolicyType.NONE);
  }

  @Test
  @DisplayName("无法识别的补跑策略编码抛出业务异常而非静默回退")
  void fromCode_unknownCode_throwsBizException() {
    assertThatThrownBy(() -> CatchUpPolicyType.fromCode("UNKNOWN"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("unknown_catch_up_policy_type_code");
  }

  @Test
  @DisplayName("宽松解析遇到非法编码时回退为不补跑,不抛异常")
  void fromCodeOrDefault_unknownCode_returnsNone() {
    assertThat(CatchUpPolicyType.fromCodeOrDefault("GARBAGE")).isEqualTo(CatchUpPolicyType.NONE);
  }

  @Test
  @DisplayName("宽松解析遇到空值或空串时回退为不补跑")
  void fromCodeOrDefault_nullOrBlank_returnsNone() {
    assertThat(CatchUpPolicyType.fromCodeOrDefault(null)).isEqualTo(CatchUpPolicyType.NONE);
    assertThat(CatchUpPolicyType.fromCodeOrDefault("")).isEqualTo(CatchUpPolicyType.NONE);
  }

  @Test
  @DisplayName("每个补跑策略的编码与展示名称都非空")
  void codeAndLabel_notBlank() {
    for (CatchUpPolicyType type : CatchUpPolicyType.values()) {
      assertThat(type.code()).isNotBlank();
      assertThat(type.label()).isNotBlank();
    }
  }
}
