package com.example.batch.worker.imports.infrastructure.quality;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.BigInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ValidationCoercionsTest {

  @Test
  @DisplayName("decimalValue:超过 double 精度的大整数走 toString,不丢精度(range 校验边界正确)")
  void decimalValue_preservesPrecision_forLargeNumber() {
    // arrange:9_999_999_999_999_999 超过 double 53 位尾数可精确表示的范围
    long highPrecision = 9_999_999_999_999_999L;

    // act
    BigDecimal coerced = ValidationCoercions.decimalValue(highPrecision);

    // assert:经 doubleValue() 会退化为 1.0E16,这里必须保留原值
    assertThat(coerced).isEqualByComparingTo(BigDecimal.valueOf(highPrecision));
    assertThat(coerced.toBigIntegerExact()).isEqualTo(BigInteger.valueOf(highPrecision));
  }

  @Test
  @DisplayName("decimalValue:BigDecimal 原样返回")
  void decimalValue_returnsBigDecimalAsIs() {
    BigDecimal value = new BigDecimal("12345.6789");
    assertThat(ValidationCoercions.decimalValue(value)).isSameAs(value);
  }

  @Test
  @DisplayName("decimalValue:字符串走精确构造,null/空白返回 null")
  void decimalValue_handlesTextAndBlanks() {
    assertThat(ValidationCoercions.decimalValue("3.14")).isEqualByComparingTo("3.14");
    assertThat(ValidationCoercions.decimalValue(null)).isNull();
    assertThat(ValidationCoercions.decimalValue("  ")).isNull();
  }

  @Test
  @DisplayName("decimalValue:非数字字符串吞异常返回 null")
  void decimalValue_returnsNull_forNonNumericText() {
    assertThat(ValidationCoercions.decimalValue("not-a-number")).isNull();
  }
}
