package io.github.pinpols.batch.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("日志字段净化:普通值/null 渲染保持原样,所有 Unicode 换行替换为下划线")
class LogSanitizerTest {

  @Test
  @DisplayName("普通字符串、数字与 null 不被改写,null 渲染为字面量 null")
  void shouldPreserveOrdinaryValuesAndNullRendering() {
    assertThat(LogSanitizer.value("tenant-a")).isEqualTo("tenant-a");
    assertThat(LogSanitizer.value(42)).isEqualTo("42");
    assertThat(LogSanitizer.value(null)).isEqualTo("null");
  }

  @Test
  @DisplayName("CRLF / LF / CR / NEL / LS / PS 六种换行全部替换为下划线,防伪造日志行")
  void shouldReplaceEveryUnicodeLineBreakWithUnderscore() {
    assertThat(LogSanitizer.value("a\r\nb\nc\rd\u0085e\u2028f\u2029g")).isEqualTo("a_b_c_d_e_f_g");
  }
}
