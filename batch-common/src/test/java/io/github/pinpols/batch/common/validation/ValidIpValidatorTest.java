package io.github.pinpols.batch.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ValidIpValidatorTest {

  private final ValidIpValidator validator = new ValidIpValidator();

  @Test
  void blankIsValid() {
    assertThat(validator.isValid(null, null)).isTrue();
    assertThat(validator.isValid("", null)).isTrue();
  }

  @Test
  void validIpv4() {
    init(false);
    assertThat(validator.isValid("192.168.1.1", null)).isTrue();
    assertThat(validator.isValid("10.0.0.1", null)).isTrue();
    assertThat(validator.isValid("0.0.0.0", null)).isTrue();
    assertThat(validator.isValid("255.255.255.255", null)).isTrue();
  }

  @Test
  void validIpv6() {
    init(false);
    assertThat(validator.isValid("::1", null)).isTrue();
    assertThat(validator.isValid("2001:db8::1", null)).isTrue();
  }

  @Test
  void ipv4OnlyRejectsIpv6() {
    init(true);
    assertThat(validator.isValid("::1", null)).isFalse();
    assertThat(validator.isValid("192.168.1.1", null)).isTrue();
  }

  @Test
  void invalidRejected() {
    init(false);
    assertThat(validator.isValid("256.1.1.1", null)).isFalse();
    assertThat(validator.isValid("not-an-ip", null)).isFalse();
    assertThat(validator.isValid("12345", null)).isFalse(); // 纯数字不算 IP
    assertThat(validator.isValid("1.2.3", null)).isFalse(); // 段数不够
  }

  private void init(boolean ipv4Only) {
    validator.initialize(
        new ValidIp() {
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
