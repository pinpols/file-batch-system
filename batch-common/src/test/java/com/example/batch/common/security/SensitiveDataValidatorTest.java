package com.example.batch.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SensitiveDataValidatorTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "password",
        "passwd",
        "secret",
        "apiKey",
        "api_key",
        "token",
        "credential",
        "accessKey",
        "access_key",
        "privateKey",
        "private_key",
        "clientSecret",
        "client_secret"
      })
  void shouldRejectAllSensitiveKeywords(String key) {
    Map<String, Object> data = Map.of(key, "anything");

    assertThatThrownBy(
            () -> SensitiveDataValidator.rejectIfContainsSensitiveKeys(data, "ctx.scope"))
        .isInstanceOf(BizException.class)
        .satisfies(
            ex -> {
              BizException biz = (BizException) ex;
              assertThat(biz.getCode()).isEqualTo(ResultCode.INVALID_ARGUMENT);
              assertThat(biz.getMessageKey()).isEqualTo("error.security.sensitive_in_payload");
              assertThat(biz.getMessageArgs()).containsExactly("ctx.scope", key);
            });
  }

  @Test
  void shouldBeCaseInsensitive() {
    assertThatThrownBy(
            () ->
                SensitiveDataValidator.rejectIfContainsSensitiveKeys(
                    Map.of("My_PASSWORD_Field", "x"), "ctx"))
        .isInstanceOf(BizException.class);
    assertThatThrownBy(
            () ->
                SensitiveDataValidator.rejectIfContainsSensitiveKeys(
                    Map.of("OAUTH_TOKEN", "x"), "ctx"))
        .isInstanceOf(BizException.class);
  }

  @Test
  void shouldDetectAsSubstring() {
    // 子串命中:db_password / x-api-key 都该被拦
    assertThatThrownBy(
            () ->
                SensitiveDataValidator.rejectIfContainsSensitiveKeys(
                    Map.of("db_password", "x"), "ctx"))
        .isInstanceOf(BizException.class);
    // kebab / snake / camel 变体均命中(validator 先 strip _/- 再比对)
    assertThatThrownBy(
            () ->
                SensitiveDataValidator.rejectIfContainsSensitiveKeys(
                    Map.of("x-api-key", "x"), "ctx"))
        .isInstanceOf(BizException.class);
    assertThatThrownBy(
            () ->
                SensitiveDataValidator.rejectIfContainsSensitiveKeys(
                    Map.of("x_api_key", "x"), "ctx"))
        .isInstanceOf(BizException.class);
  }

  @Test
  void shouldRecurseNestedMap() {
    Map<String, Object> nested =
        Map.of("url", "https://x", "auth", Map.of("user", "u", "password", "p"));

    assertThatThrownBy(
            () -> SensitiveDataValidator.rejectIfContainsSensitiveKeys(nested, "atomic.http"))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getMessageArgs()[1]).isEqualTo("password"));
  }

  @Test
  void shouldRecurseListOfMaps() {
    Map<String, Object> data = Map.of("items", List.of(Map.of("k", 1), Map.of("secret", "leak")));

    assertThatThrownBy(() -> SensitiveDataValidator.rejectIfContainsSensitiveKeys(data, "ctx"))
        .isInstanceOf(BizException.class);
  }

  @Test
  void shouldPassWhenNoSensitiveKeys() {
    Map<String, Object> data =
        Map.of("command", "/bin/echo", "args", List.of("hello"), "timeoutSeconds", 30);

    assertThatCode(() -> SensitiveDataValidator.rejectIfContainsSensitiveKeys(data, "ctx"))
        .doesNotThrowAnyException();
  }

  @Test
  void shouldHandleNullAndEmpty() {
    assertThatCode(() -> SensitiveDataValidator.rejectIfContainsSensitiveKeys(null, "ctx"))
        .doesNotThrowAnyException();
    assertThatCode(() -> SensitiveDataValidator.rejectIfContainsSensitiveKeys(Map.of(), "ctx"))
        .doesNotThrowAnyException();

    assertThat(SensitiveDataValidator.containsSensitiveKey(null)).isFalse();
    assertThat(SensitiveDataValidator.containsSensitiveKey(Map.of())).isFalse();
  }

  @Test
  void booleanVariantReturnsTrueOnHitFalseOnMiss() {
    assertThat(SensitiveDataValidator.containsSensitiveKey(Map.of("password", "x"))).isTrue();
    assertThat(SensitiveDataValidator.containsSensitiveKey(Map.of("clean", "x"))).isFalse();
    assertThat(
            SensitiveDataValidator.containsSensitiveKey(
                Map.of("nested", Map.of("a", Map.of("apiKey", "x")))))
        .isTrue();
  }

  @Test
  void shouldNotInfiniteLoopOnSelfReferentialMap() {
    Map<String, Object> a = new LinkedHashMap<>();
    Map<String, Object> b = new LinkedHashMap<>();
    a.put("b", b);
    b.put("a", a); // 自指引

    assertThatCode(() -> SensitiveDataValidator.rejectIfContainsSensitiveKeys(a, "ctx"))
        .doesNotThrowAnyException();
  }
}
