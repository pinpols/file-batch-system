package io.github.pinpols.batch.console.domain.rbac.support.captcha;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 验证码 provider 共用低层工具:SHA-256 / HMAC / hex / 日志净化。 */
@DisplayName("CaptchaCrypto:摘要 / HMAC / 日志净化")
class CaptchaCryptoTest {

  @Test
  @DisplayName("sha256Hex 命中公认向量(空串 + hello)")
  void sha256Hex_knownVectors() {
    assertThat(CaptchaCrypto.sha256Hex(""))
        .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    assertThat(CaptchaCrypto.sha256Hex("hello"))
        .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
  }

  @Test
  @DisplayName("hmacSha256Hex 64 位小写 hex、确定、绑定密钥")
  void hmacSha256Hex_deterministicAndKeyBound() {
    String a = CaptchaCrypto.hmacSha256Hex("secret", "data");
    assertThat(a)
        .hasSize(64)
        .matches("[0-9a-f]{64}")
        .isEqualTo(CaptchaCrypto.hmacSha256Hex("secret", "data"));
    assertThat(CaptchaCrypto.hmacSha256Hex("other", "data")).isNotEqualTo(a);
  }

  @Test
  @DisplayName("sanitizeForLog 去 CRLF、null 归空")
  void sanitizeForLog_stripsCrlf() {
    assertThat(CaptchaCrypto.sanitizeForLog("1.2.3.4\nFAKE LOG")).isEqualTo("1.2.3.4_FAKE LOG");
    assertThat(CaptchaCrypto.sanitizeForLog("a\r\nb")).isEqualTo("a__b");
    assertThat(CaptchaCrypto.sanitizeForLog(null)).isEqualTo("");
  }
}
