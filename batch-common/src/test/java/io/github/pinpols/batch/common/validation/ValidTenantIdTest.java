package io.github.pinpols.batch.common.validation;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 守护 {@link ValidTenantId} 组合注解：必填 + 长度(64) + 格式 pattern 三层都生效。
 *
 * <p>tenant id 比 resource code 严格 —— 允许 `.` 但首字符要求是字母或数字（不允许 `_` / `-` 开头）。
 */
@DisplayName("ValidTenantId: 租户标识组合注解的必填、长度与字符集三层约束")
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
    @ValidTenantId
    private String tenantId;
  }

  private Set<ConstraintViolation<Holder>> violations(String value) {
    return validator.validate(new Holder(value));
  }

  @ParameterizedTest
  @DisplayName("合法租户标识全部通过校验,含点号、下划线与数字开头")
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
  @DisplayName("非法租户标识被拒绝,含下划线、连字符与点号开头及中文")
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
  @DisplayName("租户标识为空时校验失败")
  void shouldReject_whenTenantIdNull() {
    assertThat(violations(null)).isNotEmpty();
  }

  @Test
  @DisplayName("租户标识只有空白字符时校验失败")
  void shouldReject_whenTenantIdBlank() {
    assertThat(violations("   ")).isNotEmpty();
  }

  @Test
  @DisplayName("长度达到上限 64 个字符时通过校验")
  void shouldAccept_whenTenantIdAtMaxLength() {
    String s = "a" + "0".repeat(63);
    assertThat(s).hasSize(64);
    assertThat(violations(s)).isEmpty();
  }

  @Test
  @DisplayName("长度超过上限 65 个字符时校验失败")
  void shouldReject_whenTenantIdExceedsMaxLength() {
    String s = "a" + "0".repeat(64); // 65
    assertThat(violations(s)).isNotEmpty();
  }
}
