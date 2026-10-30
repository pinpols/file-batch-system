package io.github.pinpols.batch.console.support.naming;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

@DisplayName("资源保留前缀校验")
@ResourceLock("java.util.Locale.default")
class ReservedPrefixGuardTest {
  @Test
  @DisplayName("土耳其语默认环境仍拒绝保留前缀并允许普通编码")
  void shouldUseLocaleIndependentReservedPrefix() {
    Locale previous = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      assertThatThrownBy(() -> ReservedPrefixGuard.checkResourceCode("internal_job", true))
          .isInstanceOf(BizException.class)
          .extracting(ex -> ((BizException) ex).getCode())
          .isEqualTo(ResultCode.INVALID_ARGUMENT);
      assertThatCode(() -> ReservedPrefixGuard.checkResourceCode("invoice_job", true))
          .doesNotThrowAnyException();
    } finally {
      Locale.setDefault(previous);
    }
  }
}
