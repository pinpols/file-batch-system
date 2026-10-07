package io.github.pinpols.batch.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("ValidBizDateValidator: 业务日期格式校验,含闰年、年月边界与空白处理")
class ValidBizDateValidatorTest {

  private final ValidBizDateValidator v = new ValidBizDateValidator();

  @ParameterizedTest
  @DisplayName("合法日期格式全部通过校验,含闰年与年月边界")
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
  @DisplayName("非法日期格式全部被拒绝,含不存在的日期、错分隔符与缺零填充")
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
  @DisplayName("空值与空白按设计放行,必填约束交由上层注解")
  void shouldAllowNullAndBlankByDesign_whenValidating() {
    // 设计:校验只关心格式,是否必填交给 @NotBlank
    assertThat(v.isValid(null, null)).isTrue();
    assertThat(v.isValid("", null)).isTrue();
    assertThat(v.isValid("   ", null)).isTrue();
  }

  @Test
  @DisplayName("首尾空白被去除后仍判定为合法日期")
  void shouldTrimWhitespace_whenValidating() {
    assertThat(v.isValid("  2026-05-20  ", null)).isTrue();
  }
}
