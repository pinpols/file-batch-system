package io.github.pinpols.batch.console.application.contract.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("审批排除类型: 只接受最多 8 个大写类型代码")
class ApprovalTypeExclusionsTest {

  @Test
  @DisplayName("空值不排除任何类型")
  void shouldReturnEmpty_whenRawIsBlank() {
    assertThat(ApprovalTypeExclusions.parse(null)).isEmpty();
    assertThat(ApprovalTypeExclusions.parse("  ")).isEmpty();
  }

  @Test
  @DisplayName("逗号分隔去重并忽略空段")
  void shouldParseDistinctCodes() {
    assertThat(ApprovalTypeExclusions.parse(" CATCH_UP, CATCH_UP, ,DOWNLOAD "))
        .containsExactly("CATCH_UP", "DOWNLOAD");
  }

  @Test
  @DisplayName("小写或超过 8 个类型直接拒绝")
  void shouldRejectInvalidCodes() {
    assertThatThrownBy(() -> ApprovalTypeExclusions.parse("catch_up"))
        .isInstanceOf(BizException.class);
    assertThatThrownBy(() -> ApprovalTypeExclusions.parse("A,B,C,D,E,F,G,H,I"))
        .isInstanceOf(BizException.class);
  }
}
