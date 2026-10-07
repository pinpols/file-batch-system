package io.github.pinpols.batch.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ValidIpValidator: IPv4 与 IPv6 地址校验及仅 IPv4 模式")
class ValidIpValidatorTest {

  private final ValidIpValidator validator = new ValidIpValidator();

  @Test
  @DisplayName("空值与空串视为合法")
  void shouldTreatBlankAsValid_whenValidating() {
    assertThat(validator.isValid(null, null)).isTrue();
    assertThat(validator.isValid("", null)).isTrue();
  }

  @Test
  @DisplayName("常见 IPv4 地址与全零、全一广播地址均通过校验")
  void shouldAcceptValidIpv4_whenValidating() {
    init(false);
    assertThat(validator.isValid("192.168.1.1", null)).isTrue();
    assertThat(validator.isValid("10.0.0.1", null)).isTrue();
    assertThat(validator.isValid("0.0.0.0", null)).isTrue();
    assertThat(validator.isValid("255.255.255.255", null)).isTrue();
  }

  @Test
  @DisplayName("IPv6 回环与文档地址通过校验")
  void shouldAcceptValidIpv6_whenValidating() {
    init(false);
    assertThat(validator.isValid("::1", null)).isTrue();
    assertThat(validator.isValid("2001:db8::1", null)).isTrue();
  }

  @Test
  @DisplayName("仅 IPv4 模式下 IPv6 地址被拒绝,IPv4 地址仍通过")
  void shouldRejectIpv6_whenIpv4OnlyEnabled() {
    init(true);
    assertThat(validator.isValid("::1", null)).isFalse();
    assertThat(validator.isValid("192.168.1.1", null)).isTrue();
  }

  @Test
  @DisplayName("越界网段、非地址文本与字段不足的输入被拒绝")
  void shouldRejectInvalidAddress_whenValidating() {
    init(false);
    assertThat(validator.isValid("256.1.1.1", null)).isFalse();
    assertThat(validator.isValid("not-an-ip", null)).isFalse();
    assertThat(validator.isValid("12345", null)).isFalse(); // 纯数字不算 IP
    assertThat(validator.isValid("1.2.3", null)).isFalse(); // 段数不够
  }

  private void init(boolean ipv4Only) {
    validator.initialize(new ValidIp() {
      @Override
      public Class<? extends java.lang.annotation.Annotation> annotationType() {
        return ValidIp.class;
      }

      @Override
      public String message() {
        return "";
      }

      @Override
      public boolean ipv4Only() {
        return ipv4Only;
      }

      @Override
      public Class<?>[] groups() {
        return new Class<?>[0];
      }

      @Override
      public Class<? extends jakarta.validation.Payload>[] payload() {
        @SuppressWarnings("unchecked")
        Class<? extends jakarta.validation.Payload>[] empty = new Class[0];
        return empty;
      }
    });
  }
}
