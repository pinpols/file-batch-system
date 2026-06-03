package com.example.batch.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 单测 {@link ApiKeyHasher} —— P1-1(docs/analysis/2026-06-03-deep-scan-be-security.md)。
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
class ApiKeyHasherTest {

  @Test
  void hashWithSaltKdf_producesUniqueSaltedHashEachCall() {
    ApiKeyHasher.SaltedHash a = ApiKeyHasher.hashWithSaltKdf("raw-key");
    ApiKeyHasher.SaltedHash b = ApiKeyHasher.hashWithSaltKdf("raw-key");
    assertThat(a.salt()).isNotEqualTo(b.salt());
    assertThat(a.hash()).isNotEqualTo(b.hash());
  }

  @Test
  void verify_acceptsCorrectPbkdf2Key() {
    ApiKeyHasher.SaltedHash sh = ApiKeyHasher.hashWithSaltKdf("raw-key");
    assertThat(ApiKeyHasher.verify("raw-key", sh.hash(), sh.salt(), "pbkdf2")).isTrue();
  }

  @Test
  void verify_rejectsWrongKey() {
    ApiKeyHasher.SaltedHash sh = ApiKeyHasher.hashWithSaltKdf("raw-key");
    assertThat(ApiKeyHasher.verify("wrong-key", sh.hash(), sh.salt(), "pbkdf2")).isFalse();
  }

  @Test
  void verify_pbkdf2_requiresSalt() {
    ApiKeyHasher.SaltedHash sh = ApiKeyHasher.hashWithSaltKdf("raw-key");
    assertThat(ApiKeyHasher.verify("raw-key", sh.hash(), null, "pbkdf2")).isFalse();
    assertThat(ApiKeyHasher.verify("raw-key", sh.hash(), "", "pbkdf2")).isFalse();
  }

  @Test
  void verify_acceptsLegacySha256() {
    String legacyHash = ApiKeyHasher.legacySha256Hex("legacy-key");
    assertThat(ApiKeyHasher.verify("legacy-key", legacyHash, null, "sha256")).isTrue();
    assertThat(ApiKeyHasher.verify("wrong-key", legacyHash, null, "sha256")).isFalse();
  }

  @Test
  void verify_rejectsUnknownAlgo() {
    assertThat(ApiKeyHasher.verify("k", "h", "s", "md5")).isFalse();
    assertThat(ApiKeyHasher.verify("k", "h", "s", null)).isFalse();
  }

  @Test
  void verify_rejectsNullInputs() {
    assertThat(ApiKeyHasher.verify(null, "h", "s", "pbkdf2")).isFalse();
    assertThat(ApiKeyHasher.verify("k", null, "s", "pbkdf2")).isFalse();
  }

  @Test
  void newSalt_isBase64_andNonEmpty() {
    String salt = ApiKeyHasher.newSalt();
    assertThat(salt).isNotBlank();
    // base64 of 16B is 24 chars w/ padding
    assertThat(salt).hasSize(24);
  }
}
