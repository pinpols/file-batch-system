package io.github.pinpols.batch.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ValidCronValidatorTest {

  private final ValidCronValidator validator = new ValidCronValidator();

  @Test
  void blankIsValid() {
    assertThat(validator.isValid(null, null)).isTrue();
    assertThat(validator.isValid("", null)).isTrue();
    assertThat(validator.isValid("   ", null)).isTrue();
  }

  @Test
  void sixFieldCron() {
    assertThat(validator.isValid("0 0 * * * *", null)).isTrue(); // 每小时
    assertThat(validator.isValid("*/30 * * * * *", null)).isTrue(); // 每 30 秒
  }

  @Test
  void fiveFieldCronAutoPadsSeconds() {
    assertThat(validator.isValid("0 * * * *", null)).isTrue(); // 每小时整点
    assertThat(validator.isValid("*/15 * * * *", null)).isTrue(); // 每 15 分
    assertThat(validator.isValid("0 9 * * 1-5", null)).isTrue(); // 工作日 9 点
  }

  @Test
  void invalidCronRejected() {
    assertThat(validator.isValid("not-cron", null)).isFalse();
    assertThat(validator.isValid("99 * * * *", null)).isFalse(); // 分钟 99 越界
    assertThat(validator.isValid("0 0", null)).isFalse(); // 字段不够
  }
}
