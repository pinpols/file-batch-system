package io.github.pinpols.batch.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ValidBizDateValidatorTest {

  private final ValidBizDateValidator v = new ValidBizDateValidator();

  @ParameterizedTest
  @ValueSource(
      strings = {
        "2026-01-01",
        "2026-12-31",
        "2026-02-28",
        "2024-02-29", // 闰年合法
        "0001-01-01",
        "9999-12-31",
      })
  void accepts_validDates(String s) {
    assertThat(v.isValid(s, null)).as(s).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "2026-02-30", // 不存在的日期
        "2026-13-01", // 不存在的月份
        "2026-00-01", // 月份为 0
        "2026-01-32", // 日期超界
        "2025-02-29", // 平年不存在 2/29
        "2026/01/01", // 错分隔符
        "26-01-01", // 短年份
        "2026-1-1", // 缺零填充
        "not-a-date",
        "today",
        "2026-01-01T00:00:00", // 含时间
      })
  void rejects_invalidDates(String s) {
    assertThat(v.isValid(s, null)).as(s).isFalse();
  }

  @Test
  void allowsNullBlankByDesign() {
    // 设计:校验只关心格式,是否必填交给 @NotBlank
    assertThat(v.isValid(null, null)).isTrue();
    assertThat(v.isValid("", null)).isTrue();
    assertThat(v.isValid("   ", null)).isTrue();
  }

  @Test
  void trimsWhitespace() {
    assertThat(v.isValid("  2026-05-20  ", null)).isTrue();
  }
}
