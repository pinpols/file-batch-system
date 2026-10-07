package io.github.pinpols.batch.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 单测 {@link ApiKeyHasher} —— P1-1(docs/archive/analysis/2026-06-03-deep-scan-be-security.md)。
 *
 * <p>覆盖:
 *
 * <ul>
 *   <li>PBKDF2 salt 不可重放(每次签发 salt 不同 → hash 不同)
 *   <li>verify 常量时间正向/反向匹配
 *   <li>legacy sha256 兼容路径(空 salt 也能比对)
 *   <li>unknown algo 拒
 * </ul>
 */
@DisplayName("ApiKeyHasher: PBKDF2 加盐哈希、常量时间校验与遗留兼容路径")
class ApiKeyHasherTest {

  @Test
  @DisplayName("同一明文重复签发时 salt 与哈希都必须不同,以抵御重放")
  void hashWithSaltKdf_producesUniqueSaltedHashEachCall() {
    ApiKeyHasher.SaltedHash a = ApiKeyHasher.hashWithSaltKdf("raw-key");
    ApiKeyHasher.SaltedHash b = ApiKeyHasher.hashWithSaltKdf("raw-key");
    assertThat(a.salt()).isNotEqualTo(b.salt());
    assertThat(a.hash()).isNotEqualTo(b.hash());
  }

  @Test
  @DisplayName("正确明文与 PBKDF2 记录比对必须通过")
  void verify_acceptsCorrectPbkdf2Key() {
    ApiKeyHasher.SaltedHash sh = ApiKeyHasher.hashWithSaltKdf("raw-key");
    assertThat(ApiKeyHasher.verify("raw-key", sh.hash(), sh.salt(), "pbkdf2")).isTrue();
  }

  @Test
  @DisplayName("错误明文比对必须失败")
  void verify_rejectsWrongKey() {
    ApiKeyHasher.SaltedHash sh = ApiKeyHasher.hashWithSaltKdf("raw-key");
    assertThat(ApiKeyHasher.verify("wrong-key", sh.hash(), sh.salt(), "pbkdf2")).isFalse();
  }

  @Test
  @DisplayName("PBKDF2 记录缺失 salt 或 salt 为空时必须判失败")
  void verify_pbkdf2_requiresSalt() {
    ApiKeyHasher.SaltedHash sh = ApiKeyHasher.hashWithSaltKdf("raw-key");
    assertThat(ApiKeyHasher.verify("raw-key", sh.hash(), null, "pbkdf2")).isFalse();
    assertThat(ApiKeyHasher.verify("raw-key", sh.hash(), "", "pbkdf2")).isFalse();
  }

  @Test
  @DisplayName("遗留 sha256 记录在无 salt 时正确明文通过,错误明文失败")
  void verify_acceptsLegacySha256() {
    String legacyHash = ApiKeyHasher.legacySha256Hex("legacy-key");
    assertThat(ApiKeyHasher.verify("legacy-key", legacyHash, null, "sha256")).isTrue();
    assertThat(ApiKeyHasher.verify("wrong-key", legacyHash, null, "sha256")).isFalse();
  }

  @Test
  @DisplayName("未知算法名或算法为空时必须拒绝")
  void verify_rejectsUnknownAlgo() {
    assertThat(ApiKeyHasher.verify("k", "h", "s", "md5")).isFalse();
    assertThat(ApiKeyHasher.verify("k", "h", "s", null)).isFalse();
  }

  @Test
  @DisplayName("明文或记录哈希为空时必须拒绝")
  void verify_rejectsNullInputs() {
    assertThat(ApiKeyHasher.verify(null, "h", "s", "pbkdf2")).isFalse();
    assertThat(ApiKeyHasher.verify("k", null, "s", "pbkdf2")).isFalse();
  }

  @Test
  @DisplayName("新签发的 salt 非空且为 16 字节 Base64 定长 24 字符")
  void newSalt_isBase64_andNonEmpty() {
    String salt = ApiKeyHasher.newSalt();
    assertThat(salt).isNotBlank();
    // base64 of 16B is 24 chars w/ padding
    assertThat(salt).hasSize(24);
  }
}
