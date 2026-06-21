package com.example.batch.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 守护 {@link ValidResourceCode} 注解组合的有效性：必填 + 长度 + 字符集 pattern 三层都生效。
 *
 * <p>注解组合是 Bean Validation 元注解机制 + Lombok/Hibernate Validator 实现，单测覆盖典型异常数据 + 边界长度。
 */
class ValidResourceCodeTest {

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void beforeAll() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void afterAll() {
    if (factory != null) factory.close();
  }

  @NoArgsConstructor
  @AllArgsConstructor
  @Setter
  static class Holder {
    @ValidResourceCode private String code;
  }

  private Set<ConstraintViolation<Holder>> violations(String value) {
    return validator.validate(new Holder(value));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "abc",
        "ABC",
        "a_b",
        "a-b",
        "a1",
        "Abc_123-XYZ",
        "j", // 单字符也接受
      })
  void accepts_validCodes(String code) {
    assertThat(violations(code)).as("应接受合法 code: %s", code).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "q q q", // 含空格(就是历史异常数据案例)
        " leading", // 前导空格
        "trailing ", // 尾随空格
        "with空格", // 中文空格
        "中文",
        "test中文", // 含中文
        "1abc", // 数字开头
        "_abc", // 下划线开头
        "-abc", // 连字符开头
        "abc.def", // 含点
        "abc/def", // 含斜杠
        "abc!", // 特殊字符
        "abc@def",
        "abc#def",
        "abc\ndef", // 含换行
        "abc\tdef", // 含 tab
      })
  void rejects_invalidCharsets(String code) {
    assertThat(violations(code)).as("应拒绝非法 code: %s", code).isNotEmpty();
  }

  @Test
  void rejectsNull() {
    assertThat(violations(null)).isNotEmpty();
  }

  @Test
  void rejectsEmpty() {
    assertThat(violations("")).isNotEmpty();
  }

  @Test
  void rejectsBlank() {
    assertThat(violations("   ")).isNotEmpty();
  }

  @Test
  void acceptsMax128() {
    String s = "a" + "0".repeat(127); // 128 字符
    assertThat(s).hasSize(128);
    assertThat(violations(s)).isEmpty();
  }

  @Test
  void rejectsOver128() {
    String s = "a" + "0".repeat(128); // 129 字符
    assertThat(s).hasSize(129);
    assertThat(violations(s)).isNotEmpty();
  }
}
