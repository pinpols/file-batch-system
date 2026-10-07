package io.github.pinpols.batch.console.domain.rbac.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("控制台口令哈希:编码为 Argon2id 且可校验, 种子哈希匹配, 非该算法哈希拒绝")
class ConsolePasswordHasherTest {

  /** 与 batch-orchestrator Flyway V34 控制台默认种子一致；明文为 admin123。 */
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
  @DisplayName("种子校验:与初始化脚本中的哈希匹配成功")
  void shouldMatchFlywaySeedArgon2Hash() {
    assertThat(passwordHasher.matches("admin123", SEED_ARGON2_ADMIN123)).isTrue();
  }

  @Test
  @DisplayName("算法不符:非 Argon2id 哈希抛出业务异常")
  void shouldRejectNonArgon2Hash() {
    assertThatThrownBy(() -> passwordHasher.matches("x", "pbkdf2_sha256$120000$salt$hash"))
        .isInstanceOf(BizException.class);
  }
}
