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
 * 守护 {@link ValidTenantId} 组合注解：必填 + 长度(64) + 格式 pattern 三层都生效。
 *
 * <p>tenant id 比 resource code 严格 —— 允许 `.` 但首字符要求是字母或数字（不允许 `_` / `-` 开头）。
 */
class ValidTenantIdTest {

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
    @ValidTenantId private String tenantId;
  }

  private Set<ConstraintViolation<Holder>> violations(String value) {
    return validator.validate(new Holder(value));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ta",
        "default-tenant",
        "tenant_01",
        "tenant.name",
        "TenantABC",
        "abc-_.123", // 含全部允许特殊符号
        "1abc", // 数字开头(与 resource code 不同,这里允许)
      })
  void accepts_validTenantIds(String v) {
    assertThat(violations(v)).as("应接受 tenantId: %s", v).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "_abc", // 下划线开头
        "-abc", // 连字符开头
        ".abc", // 点号开头
        "中文", // 中文
        "a b", // 空格
        "abc!", // 特殊字符
        "abc/def",
      })
  void rejects_invalidCharsets(String v) {
    assertThat(violations(v)).as("应拒绝 tenantId: %s", v).isNotEmpty();
  }

  @Test
  void rejectsNull() {
    assertThat(violations(null)).isNotEmpty();
  }

  @Test
  void rejectsBlank() {
    assertThat(violations("   ")).isNotEmpty();
  }

  @Test
  void acceptsMax64() {
    String s = "a" + "0".repeat(63);
    assertThat(s).hasSize(64);
    assertThat(violations(s)).isEmpty();
  }

  @Test
  void rejectsOver64() {
    String s = "a" + "0".repeat(64); // 65
    assertThat(violations(s)).isNotEmpty();
  }
}
