package io.github.pinpols.batch.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** ADR-039 P1 envRef 解析 + fail-fast + 明文兼容期放行。 */
@DisplayName("CredentialEnvResolver: 环境变量引用解析、缺失快速失败与明文兼容放行")
class CredentialEnvResolverTest {

  private static final UnaryOperator<String> ENV =
      Map.of("DB_PASSWORD", "s3cr3t", "API_TOKEN", "tok-123")::get;

  @Test
  @DisplayName("引用已定义的变量时返回真实值")
  void resolvesEnvRef_whenDefined() {
    assertThat(CredentialEnvResolver.resolve("${DB_PASSWORD}", ENV)).isEqualTo("s3cr3t");
    assertThat(CredentialEnvResolver.resolve("${API_TOKEN}", ENV)).isEqualTo("tok-123");
  }

  @Test
  @DisplayName("引用未定义变量时按未解析凭据错误码快速失败")
  void failsFast_whenEnvRefUndefined() {
    assertThatThrownBy(() -> CredentialEnvResolver.resolve("${MISSING_SECRET}", ENV))
        .isInstanceOf(BizException.class)
        .extracting(e -> ((BizException) e).getCode())
        .isEqualTo(ResultCode.CREDENTIAL_REF_UNRESOLVED);
  }

  @Test
  @DisplayName("变量已定义但取值为空时同样快速失败")
  void failsFast_whenEnvRefDefinedButEmpty() {
    UnaryOperator<String> blank = name -> "";
    assertThatThrownBy(() -> CredentialEnvResolver.resolve("${DB_PASSWORD}", blank))
        .isInstanceOf(BizException.class);
  }

  @ParameterizedTest
  @DisplayName("非严格整串引用形态原样放行,不误抛异常")
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
  @DisplayName("入参为空时直接返回空值")
  void returnsNull_whenNull() {
    assertThat(CredentialEnvResolver.resolve(null, ENV)).isNull();
  }
}
