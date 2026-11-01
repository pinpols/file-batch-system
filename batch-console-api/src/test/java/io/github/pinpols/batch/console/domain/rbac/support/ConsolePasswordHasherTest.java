package io.github.pinpols.batch.console.domain.rbac.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("控制台口令哈希:编码为 Argon2id 且可校验, 固定哈希样本匹配, 非该算法哈希拒绝")
class ConsolePasswordHasherTest {

  /** 固定 Argon2id 样本，明文为 admin123；此测试不读取迁移文件。 */
  static final String SEED_ARGON2_ADMIN123 =
      "$argon2id$v=19$m=16384,t=2,p=1$k18enAVVcHofGDMPXPxj5A$5TityFxKIX2z6bkuDXRHqmwuPcfr+G9MEA36Kr6fC4s";

  private final ConsolePasswordHasher passwordHasher = new ConsolePasswordHasher();

  @Test
  @DisplayName("编码:结果以 Argon2id 前缀开头, 且原口令校验通过")
  void shouldEncodeToArgon2id() {
    String encoded = passwordHasher.encode("admin123");

    assertThat(encoded).startsWith("$argon2id$");
    assertThat(passwordHasher.matches("admin123", encoded)).isTrue();
  }

  @Test
  @DisplayName("固定样本校验:已知 Argon2id 哈希与明文匹配")
  void shouldMatchKnownArgon2Hash() {
    assertThat(passwordHasher.matches("admin123", SEED_ARGON2_ADMIN123)).isTrue();
  }

  @Test
  @DisplayName("算法不符:非 Argon2id 哈希抛出业务异常")
  void shouldRejectNonArgon2Hash() {
    assertThatThrownBy(() -> passwordHasher.matches("x", "pbkdf2_sha256$120000$salt$hash"))
        .isInstanceOf(BizException.class);
  }
}
