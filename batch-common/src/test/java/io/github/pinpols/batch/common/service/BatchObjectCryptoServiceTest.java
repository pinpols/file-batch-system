package io.github.pinpols.batch.common.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.config.BatchKmsProperties;
import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link BatchObjectCryptoService} 的单元测试 —— 验证 AES-GCM 加解密往返， 包括 StoreStep（导出）使用的 BATCHENC 魔数头格式以及
 * PreprocessStep KMS 闭包（导入）。
 */
@DisplayName("对象加解密服务:字节、流与文件三种形态的加解密往返,魔数头格式以及开关与密钥解析")
class BatchObjectCryptoServiceTest {

  // 32-byte AES-256 test key (random, base64-encoded)
  private static final String KEY_REF = "TEST_KEY_2026";
  private static final String KEY_B64 = Base64.getEncoder()
      .encodeToString("01234567890123456789012345678901".getBytes(StandardCharsets.US_ASCII));

  private BatchObjectCryptoService cryptoService;

  @BeforeEach
  void setUp() {
    BatchSecurityProperties security = new BatchSecurityProperties();
    security.setBypassMode(false);

    BatchKmsProperties kms = new BatchKmsProperties();
    kms.setDefaultKeyRef(KEY_REF);
    kms.setKeys(Map.of(KEY_REF, KEY_B64));

    cryptoService = new BatchObjectCryptoService(security, kms);
  }

  // ── byte[] 加解密往返 ──────────────────────────────────────────────────────

  @Test
  @DisplayName("同一密钥加密后再解密:字节数组内容完整还原")
  void encryptAndDecryptBytes_shouldRoundTrip() {
    byte[] plaintext = "Hello, BATCHENC!".getBytes(StandardCharsets.UTF_8);

    byte[] ciphertext = cryptoService.encrypt(plaintext, KEY_REF);
    byte[] recovered = cryptoService.decrypt(ciphertext);

    assertThat(recovered).isEqualTo(plaintext);
  }

  @Test
  @DisplayName("加密结果前置 8 字节魔数头,便于导入侧识别已加密内容")
  void encryptedBytes_shouldStartWithMagicHeader() {
    byte[] ciphertext = cryptoService.encrypt("test".getBytes(StandardCharsets.UTF_8), KEY_REF);

    // BATCHENC 魔数（8 字节）
    assertThat(new String(ciphertext, 0, 8, StandardCharsets.US_ASCII)).isEqualTo("BATCHENC");
  }

  @Test
  @DisplayName("解密未加密内容:原样返回,不改动明文")
  void decryptUnencryptedBytes_shouldReturnOriginal() {
    byte[] plain = "not encrypted".getBytes(StandardCharsets.UTF_8);

    byte[] result = cryptoService.decrypt(plain);

    assertThat(result).isEqualTo(plain);
  }

  @Test
  @DisplayName("解密输入为空:返回空值")
  void decryptNullBytes_shouldReturnNull() {
    assertThat(cryptoService.decrypt(null)).isNull();
  }

  @Test
  @DisplayName("解密空字节数组:返回空数组")
  void decryptEmptyBytes_shouldReturnEmpty() {
    assertThat(cryptoService.decrypt(new byte[0])).isEmpty();
  }

  @Test
  @DisplayName("相同明文两次加密:随机初始向量使密文不同,但都能还原出同一明文")
  void encryptWithDifferentCalls_shouldProduceDifferentCiphertext() {
    byte[] plaintext = "same plaintext".getBytes(StandardCharsets.UTF_8);

    byte[] cipher1 = cryptoService.encrypt(plaintext, KEY_REF);
    byte[] cipher2 = cryptoService.encrypt(plaintext, KEY_REF);

    // 每次调用使用不同的 IV → 产生不同的密文
    assertThat(cipher1).isNotEqualTo(cipher2);
    // 两者解密后得到相同的明文
    assertThat(cryptoService.decrypt(cipher1)).isEqualTo(plaintext);
    assertThat(cryptoService.decrypt(cipher2)).isEqualTo(plaintext);
  }

  // ── 流式加解密往返 ────────────────────────────────────────────────────────

  @Test
  @DisplayName("流式加密后再流式解密:内容完整还原")
  void encryptStream_thenDecryptStream_shouldRoundTrip() throws Exception {
    byte[] plaintext = "streaming content".getBytes(StandardCharsets.UTF_8);

    ByteArrayOutputStream encOut = new ByteArrayOutputStream();
    cryptoService.encrypt(new ByteArrayInputStream(plaintext), encOut, KEY_REF);

    byte[] encrypted = encOut.toByteArray();
    try (InputStream decryptedStream =
        cryptoService.decryptIfNeeded(new ByteArrayInputStream(encrypted))) {
      byte[] recovered = decryptedStream.readAllBytes();
      assertThat(recovered).isEqualTo(plaintext);
    }
  }

  @Test
  @DisplayName("流式解密遇到未加密数据:原样透传")
  void decryptIfNeeded_onPlainStream_shouldPassThrough() throws Exception {
    byte[] plain = "plain data".getBytes(StandardCharsets.UTF_8);

    try (InputStream result = cryptoService.decryptIfNeeded(new ByteArrayInputStream(plain))) {
      assertThat(result.readAllBytes()).isEqualTo(plain);
    }
  }

  // ── 文件加解密往返 ─────────────────────────────────────────────────────────

  @Test
  @DisplayName("文件加密后按字节解密:内容与源文件一致")
  void encryptFile_thenDecryptBytes_shouldRoundTrip() throws Exception {
    byte[] content = "file content for KMS test".getBytes(StandardCharsets.UTF_8);
    Path source = Files.createTempFile("kms-test-src-", ".txt");
    Path encrypted = Files.createTempFile("kms-test-enc-", ".bin");
    try {
      Files.write(source, content);

      cryptoService.encrypt(source, encrypted, KEY_REF);

      byte[] encryptedBytes = Files.readAllBytes(encrypted);
      byte[] decrypted = cryptoService.decrypt(encryptedBytes);
      assertThat(decrypted).isEqualTo(content);
    } finally {
      Files.deleteIfExists(source);
      Files.deleteIfExists(encrypted);
    }
  }

  // ── shouldEncrypt / resolveKeyRef 测试 ─────────────────────────────────────

  @Test
  @DisplayName("内容加密开关为真且旁路未开启:判定需要加密")
  void shouldEncrypt_whenFlagTrueAndTestingNotOpen_returnsTrue() {
    assertThat(cryptoService.shouldEncrypt(Map.of("content_encryption_enabled", Boolean.TRUE)))
        .isTrue();
  }

  @Test
  @DisplayName("内容加密开关为假:判定不需要加密")
  void shouldEncrypt_whenFlagFalse_returnsFalse() {
    assertThat(cryptoService.shouldEncrypt(Map.of("content_encryption_enabled", Boolean.FALSE)))
        .isFalse();
  }

  @Test
  @DisplayName("旁路模式开启:即使内容加密开关为真也判定不需要加密")
  void shouldEncrypt_whenTestingOpen_returnsFalse() {
    BatchSecurityProperties openSecurity = new BatchSecurityProperties();
    openSecurity.setBypassMode(true);
    BatchKmsProperties kms = new BatchKmsProperties();
    kms.setDefaultKeyRef(KEY_REF);
    kms.setKeys(Map.of(KEY_REF, KEY_B64));
    BatchObjectCryptoService openService = new BatchObjectCryptoService(openSecurity, kms);

    assertThat(openService.shouldEncrypt(Map.of("content_encryption_enabled", Boolean.TRUE)))
        .isFalse();
  }

  @Test
  @DisplayName("安全上下文带有密钥引用:优先使用该引用")
  void resolveKeyRef_usesSecurityMapKeyRef_whenPresent() {
    String ref = cryptoService.resolveKeyRef(Map.of("encryption_key_ref", KEY_REF));
    assertThat(ref).isEqualTo(KEY_REF);
  }

  @Test
  @DisplayName("安全上下文为空:回退到配置的默认密钥引用")
  void resolveKeyRef_fallsBackToDefault_whenSecurityMapEmpty() {
    String ref = cryptoService.resolveKeyRef(Map.of());
    assertThat(ref).isEqualTo(KEY_REF);
  }

  @Test
  @DisplayName("加密时密钥材料缺失:抛出异常并提示缺少密钥材料")
  void encrypt_withMissingKeyMaterial_shouldThrow() {
    assertThatThrownBy(
            () -> cryptoService.encrypt("test".getBytes(StandardCharsets.UTF_8), "NONEXISTENT_KEY"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("missing kms key material");
  }
}
