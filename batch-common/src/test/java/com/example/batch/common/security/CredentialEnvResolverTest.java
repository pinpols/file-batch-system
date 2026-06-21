package com.example.batch.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** ADR-039 P1 envRef 解析 + fail-fast + 明文兼容期放行。 */
class CredentialEnvResolverTest {

  private static final UnaryOperator<String> ENV =
      Map.of("DB_PASSWORD", "s3cr3t", "API_TOKEN", "tok-123")::get;

  @Test
  void resolvesEnvRef_whenDefined() {
    assertThat(CredentialEnvResolver.resolve("${DB_PASSWORD}", ENV)).isEqualTo("s3cr3t");
    assertThat(CredentialEnvResolver.resolve("${API_TOKEN}", ENV)).isEqualTo("tok-123");
  }

  @Test
  void failsFast_whenEnvRefUndefined() {
    assertThatThrownBy(() -> CredentialEnvResolver.resolve("${MISSING_SECRET}", ENV))
        .isInstanceOf(BizException.class)
        .extracting(e -> ((BizException) e).getCode())
        .isEqualTo(ResultCode.CREDENTIAL_REF_UNRESOLVED);
  }

  @Test
  void failsFast_whenEnvRefDefinedButEmpty() {
    UnaryOperator<String> blank = name -> "";
    assertThatThrownBy(() -> CredentialEnvResolver.resolve("${DB_PASSWORD}", blank))
        .isInstanceOf(BizException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "hunter2", // 明文
        "$DB_PASSWORD", // 缺大括号
        "${lower_case}", // 小写不匹配
        "${1BAD}", // 数字开头不匹配
        "${DB_PASSWORD}-suffix", // 非严格整串
        "prefix-${DB_PASSWORD}", // 非严格整串
        "${secret:vault://x}" // P3 占位,P1 不解析当明文放行
      })
  void passesThroughPlaintext_whenNotStrictEnvRef(String raw) {
    // 兼容期:非严格 ${ENV_NAME} 形态原样返回,不破坏现有明文配置,也不误抛
    assertThat(CredentialEnvResolver.resolve(raw, ENV)).isEqualTo(raw);
  }

  @Test
  void returnsNull_whenNull() {
    assertThat(CredentialEnvResolver.resolve(null, ENV)).isNull();
  }
}
