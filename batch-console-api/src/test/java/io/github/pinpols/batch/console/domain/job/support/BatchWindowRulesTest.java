package io.github.pinpols.batch.console.domain.job.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 导入结构可省略时间字段，但真实写入仍须以稳定业务错误拒绝缺失值，不能抛空指针。 */
@DisplayName("共同窗口写入规则：缺失字段的错误身份")
class BatchWindowRulesTest {

  @ParameterizedTest
  @ValueSource(strings = {"timezone", "startTime", "endTime"})
  @DisplayName("缺失必需时区或时间时返回固定字段上下文")
  void shouldRejectMissingRequiredValue_withStableContext(String field) {
    String timezone = field.equals("timezone") ? null : "UTC";
    String start = field.equals("startTime") ? null : "01:00";
    String end = field.equals("endTime") ? null : "02:00";

    assertThatThrownBy(() -> BatchWindowRules.validate(timezone, start, end, false))
        .isInstanceOfSatisfying(BizException.class, failure -> {
          assertThat(failure.getCode()).isEqualTo(ResultCode.INVALID_ARGUMENT);
          assertThat(failure.getMessageKey()).isEqualTo(ResultCode.INVALID_ARGUMENT.detailKey());
          assertThat(failure.getMessageArgs()).containsExactly(field);
        });
  }
}
