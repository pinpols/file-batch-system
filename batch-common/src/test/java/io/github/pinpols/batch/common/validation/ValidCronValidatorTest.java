package io.github.pinpols.batch.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ValidCronValidator: cron 表达式校验,含空值放行与五位字段自动补秒")
class ValidCronValidatorTest {

  private final ValidCronValidator validator = new ValidCronValidator();

  @Test
  @DisplayName("空值与空白视为合法,是否必填交由上层约束")
  void shouldTreatBlankAsValid_whenValidating() {
    assertThat(validator.isValid(null, null)).isTrue();
    assertThat(validator.isValid("", null)).isTrue();
    assertThat(validator.isValid("   ", null)).isTrue();
  }

  @Test
  @DisplayName("标准六位字段表达式全部通过校验")
  void shouldAcceptSixFieldCron_whenValidating() {
    assertThat(validator.isValid("0 0 * * * *", null)).isTrue(); // 每小时
    assertThat(validator.isValid("*/30 * * * * *", null)).isTrue(); // 每 30 秒
  }

  @Test
  @DisplayName("五位字段表达式自动补秒后通过校验")
  void shouldAcceptFiveFieldCronByAutoPaddingSeconds_whenValidating() {
    assertThat(validator.isValid("0 * * * *", null)).isTrue(); // 每小时整点
    assertThat(validator.isValid("*/15 * * * *", null)).isTrue(); // 每 15 分
    assertThat(validator.isValid("0 9 * * 1-5", null)).isTrue(); // 工作日 9 点
  }

  @Test
  @DisplayName("无法解析、字段越界或字段不足的表达式被拒绝")
  void shouldRejectInvalidCron_whenValidating() {
    assertThat(validator.isValid("not-cron", null)).isFalse();
    assertThat(validator.isValid("99 * * * *", null)).isFalse(); // 分钟 99 越界
    assertThat(validator.isValid("0 0", null)).isFalse(); // 字段不够
  }
}
