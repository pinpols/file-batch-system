package io.github.pinpols.batch.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * API key 哈希工具——P1-1(docs/analysis/2026-06-03-deep-scan-be-security.md)。
 *
 * <p>历史:V47 起 api_key.key_hash 是裸 SHA-256(无盐,无 KDF),DB 备份泄露后存在弱 key rainbow-table 暴力枚举风险。V166 起新增
 * {@code salt} + {@code key_hash_algo} 列,新 key 用 PBKDF2-HMAC-SHA256(600k iter) 算 + per-key 16B
 * salt;老 key {@code key_hash_algo='sha256'} 走原路径,验证命中后由调用方升级到 KDF。
 *
 * <p>**不依赖任何 Spring / 第三方 KDF lib**——纯 JDK {@code SecretKeyFactory("PBKDF2WithHmacSHA256")} 保证
 * batch-common 不引入新依赖。
 *
 * <p>调用方:
 *
 * <ul>
 *   <li>{@code ConsoleApiKeyService.create}:签发新 key 时调 {@link #hashWithSaltKdf(String)}, 存 {@code
 *       (salt, key_hash, key_hash_algo='pbkdf2')}。
 *   <li>{@code ApiKeyVerifier.verify}:按 {@code key_prefix} 索引拿候选行,逐行按其 algo + salt 用 {@link
 *       #verify(String, String, String, String)} 比对,命中后 best-effort 升级老行。
 * </ul>
 */
public final class ApiKeyHasher {

  /** OWASP 2023 cheat sheet 推荐下限。10ms 量级,worker→orchestrator 内调路径可接受。 */
  public static final int PBKDF2_ITERATIONS = 600_000;

  public static final int SALT_BYTES = 16;
  public static final int KEY_BYTES = 32;
  public static final String PBKDF2_ALGO = "PBKDF2WithHmacSHA256";

  public static final String ALGO_PBKDF2 = "pbkdf2";
  public static final String ALGO_SHA256_LEGACY = "sha256";

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private ApiKeyHasher() {}

  /** 生成 base64 编码的 16 字节随机 salt。 */
  public static String newSalt() {
    byte[] salt = new byte[SALT_BYTES];
    SECURE_RANDOM.nextBytes(salt);
    return Base64.getEncoder().encodeToString(salt);
  }

  /** 旧 SHA-256 hex hash —— 仅用于 legacy 行兼容比对,**禁用于新签发**。 */
  public static String legacySha256Hex(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  /** 新 KDF:返回 {@link SaltedHash} 含 salt + hash(base64),写入 (salt, key_hash, algo='pbkdf2')。 */
  public static SaltedHash hashWithSaltKdf(String rawKey) {
    String salt = newSalt();
    String hash = pbkdf2(rawKey, salt);
    return new SaltedHash(salt, hash);
  }

  /**
   * 常量时间验证。
   *
   * @param rawKey 客户端原文
   * @param storedHash DB 存的 hash(legacy 是 hex SHA-256,新算法是 base64 PBKDF2 output)
   * @param storedSalt DB 存的 salt(legacy null);PBKDF2 必须非空
   * @param algo {@link #ALGO_PBKDF2} 或 {@link #ALGO_SHA256_LEGACY}
   */
  public static boolean verify(String rawKey, String storedHash, String storedSalt, String algo) {
    if (rawKey == null || storedHash == null || algo == null) return false;
    String computed;
    if (ALGO_PBKDF2.equals(algo)) {
      if (storedSalt == null || storedSalt.isBlank()) return false;
      computed = pbkdf2(rawKey, storedSalt);
    } else if (ALGO_SHA256_LEGACY.equals(algo)) {
      computed = legacySha256Hex(rawKey);
    } else {
      return false;
    }
    return MessageDigest.isEqual(
        computed.getBytes(StandardCharsets.UTF_8), storedHash.getBytes(StandardCharsets.UTF_8));
  }

  private static String pbkdf2(String raw, String saltBase64) {
    try {
      byte[] salt = Base64.getDecoder().decode(saltBase64);
      KeySpec spec = new PBEKeySpec(raw.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BYTES * 8);
      SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALGO);
      byte[] out = factory.generateSecret(spec).getEncoded();
      return Base64.getEncoder().encodeToString(out);
    } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
      throw new IllegalStateException("PBKDF2 derivation failed", e);
    }
  }

  /** salt(base64) + hash(base64) pair —— 写入 (api_key.salt, api_key.key_hash) 用。 */
  public record SaltedHash(String salt, String hash) {}
}
