package io.github.pinpols.batch.common.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.config.BatchKmsProperties;
import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.service.BatchObjectCryptoService;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link EncryptingObjectStore} 单测：覆盖 put/get round-trip / bypass 透传 / getFrom 拒绝 / 其它透传。 */
@DisplayName("加密对象存储:验证写入加密与读取解密、直传写入与范围读取的拒绝、超限与未知长度拦截、绕过模式透传及委托行为")
class EncryptingObjectStoreTest {

  private static final String BUCKET = "enc-bucket";
  private static final String KEY_REF = "DEFAULT_TEST";
  private static final String PRESIGN_SECRET = "test-presign-secret-1234567890";
  private static final String DOWNLOAD_BASE_URL =
      "https://example.invalid/api/console/files/fs-download";

  private FilesystemObjectStore newRaw(Path root) {
    return new FilesystemObjectStore(root.toString(), DOWNLOAD_BASE_URL, PRESIGN_SECRET);
  }

  private BatchObjectCryptoService newCrypto(BatchSecurityProperties securityProperties) {
    BatchKmsProperties kms = new BatchKmsProperties();
    kms.setDefaultKeyRef(KEY_REF);
    byte[] keyBytes = new byte[32];
    for (int i = 0; i < keyBytes.length; i++) {
      keyBytes[i] = (byte) i;
    }
    kms.getKeys().put(KEY_REF, Base64.getEncoder().encodeToString(keyBytes));
    return new BatchObjectCryptoService(securityProperties, kms);
  }

  @Test
  @DisplayName("写入后底层存放的是带加密标识的密文,经加密层读取可还原明文")
  void shouldEncryptOnPutAndDecryptOnGet(@TempDir Path root) throws Exception {
    BatchSecurityProperties security = new BatchSecurityProperties();
    security.setBypassMode(false);
    FilesystemObjectStore raw = newRaw(root);
    BatchObjectCryptoService crypto = newCrypto(security);
    EncryptingObjectStore store = new EncryptingObjectStore(raw, crypto, security, KEY_REF);

    byte[] plaintext = "very-secret-payload".getBytes(StandardCharsets.UTF_8);
    store.put(BUCKET, "enc.bin", new ByteArrayInputStream(plaintext), plaintext.length, "x");

    // raw 存的是密文（含 BATCHENC magic），不等于明文
    try (InputStream rawIn = raw.get(BUCKET, "enc.bin")) {
      byte[] cipher = rawIn.readAllBytes();
      assertThat(cipher).isNotEqualTo(plaintext);
      assertThat(new String(cipher, 0, 8, StandardCharsets.US_ASCII)).isEqualTo("BATCHENC");
    }

    // 经 EncryptingObjectStore get 拿到的是明文
    try (InputStream in = store.get(BUCKET, "enc.bin")) {
      assertThat(in.readAllBytes()).isEqualTo(plaintext);
    }
  }

  @Test
  @DisplayName("不支持直传写入预签名,调用即抛异常以避免绕过加密写明文")
  void shouldBlockPresignPutToAvoidPlaintextBypass(@TempDir Path root) {
    BatchSecurityProperties security = new BatchSecurityProperties();
    security.setBypassMode(false);
    EncryptingObjectStore store =
        new EncryptingObjectStore(newRaw(root), newCrypto(security), security, KEY_REF);
    // 加密层不支持 PUT 预签名(直传会绕过加密写明文)
    assertThat(store.supportsPresignPut()).isFalse();
    assertThatThrownBy(() -> store.presignPut(BUCKET, "x.bin", Duration.ofMinutes(1), "text/plain"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("bypass encryption");
  }

  @Test
  @DisplayName("批量删除直接委托底层实现,目标对象被真实移除")
  void shouldDelegateDeleteMany_whenBatchDelete(@TempDir Path root) {
    BatchSecurityProperties security = new BatchSecurityProperties();
    security.setBypassMode(false);
    FilesystemObjectStore raw = newRaw(root);
    EncryptingObjectStore store =
        new EncryptingObjectStore(raw, newCrypto(security), security, KEY_REF);
    byte[] p = "z".getBytes(StandardCharsets.UTF_8);
    store.put(BUCKET, "a.bin", new ByteArrayInputStream(p), p.length, "x");
    store.put(BUCKET, "b.bin", new ByteArrayInputStream(p), p.length, "x");

    store.deleteMany(BUCKET, List.of("a.bin", "b.bin"));

    assertThat(raw.exists(BUCKET, "a.bin")).isFalse();
    assertThat(raw.exists(BUCKET, "b.bin")).isFalse();
  }

  @Test
  @DisplayName("载荷超过内存加密上限时拒绝写入,底层不落任何文件")
  void shouldRejectPayloadAboveInMemoryEncryptLimit(@TempDir Path root) {
    BatchSecurityProperties security = new BatchSecurityProperties();
    security.setBypassMode(false);
    FilesystemObjectStore raw = newRaw(root);
    BatchObjectCryptoService crypto = newCrypto(security);
    EncryptingObjectStore store = new EncryptingObjectStore(raw, crypto, security, KEY_REF, 1024);
    byte[] plaintext = new byte[2048];

    assertThatThrownBy(() -> store.put(
            BUCKET, "too-large.bin", new ByteArrayInputStream(plaintext), plaintext.length, "x"))
        .isInstanceOf(ObjectStoreException.class)
        .hasMessageContaining("in-memory encryption limit");
    assertThat(raw.exists(BUCKET, "too-large.bin")).isFalse();
  }

  @Test
  @DisplayName("调用方低报长度但实际字节超限时拒绝写入,底层不落文件")
  void shouldRejectActualBytesAboveLimitWhenCallerUnderReportsSize(@TempDir Path root) {
    BatchSecurityProperties security = new BatchSecurityProperties();
    security.setBypassMode(false);
    FilesystemObjectStore raw = newRaw(root);
    BatchObjectCryptoService crypto = newCrypto(security);
    EncryptingObjectStore store = new EncryptingObjectStore(raw, crypto, security, KEY_REF, 1024);
    byte[] plaintext = new byte[2048];

    assertThatThrownBy(() ->
            store.put(BUCKET, "under-reported.bin", new ByteArrayInputStream(plaintext), 1, "x"))
        .isInstanceOf(ObjectStoreException.class)
        .hasMessageContaining("read limit");
    assertThat(raw.exists(BUCKET, "under-reported.bin")).isFalse();
  }

  @Test
  @DisplayName("长度未知时拒绝加密写入,底层不落文件")
  void shouldRejectUnknownSizeWhenEncrypting(@TempDir Path root) {
    BatchSecurityProperties security = new BatchSecurityProperties();
    security.setBypassMode(false);
    FilesystemObjectStore raw = newRaw(root);
    BatchObjectCryptoService crypto = newCrypto(security);
    EncryptingObjectStore store = new EncryptingObjectStore(raw, crypto, security, KEY_REF, 1024);
    byte[] plaintext = "unknown-size".getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(
            () -> store.put(BUCKET, "unknown.bin", new ByteArrayInputStream(plaintext), -1, "x"))
        .isInstanceOf(ObjectStoreException.class)
        .hasMessageContaining("in-memory encryption limit");
    assertThat(raw.exists(BUCKET, "unknown.bin")).isFalse();
  }

  @Test
  @DisplayName("开启绕过模式后底层存放明文,读取也原样透传")
  void shouldPassThroughPlaintext_whenBypassEnabled(@TempDir Path root) throws Exception {
    BatchSecurityProperties security = new BatchSecurityProperties();
    security.setBypassMode(true);
    FilesystemObjectStore raw = newRaw(root);
    BatchObjectCryptoService crypto = newCrypto(security);
    EncryptingObjectStore store = new EncryptingObjectStore(raw, crypto, security, KEY_REF);

    byte[] plaintext = "no-encrypt-in-bypass".getBytes(StandardCharsets.UTF_8);
    store.put(BUCKET, "p.bin", new ByteArrayInputStream(plaintext), plaintext.length, "x");

    // raw 存的就是明文
    try (InputStream rawIn = raw.get(BUCKET, "p.bin")) {
      assertThat(rawIn.readAllBytes()).isEqualTo(plaintext);
    }
    // get 也直透
    try (InputStream in = store.get(BUCKET, "p.bin")) {
      assertThat(in.readAllBytes()).isEqualTo(plaintext);
    }
  }

  @Test
  @DisplayName("即使处于绕过模式,范围读取仍被拒绝并给出明确提示")
  void shouldRejectRangeRead_whenRangeReadRequestedInBypass(@TempDir Path root) {
    BatchSecurityProperties security = new BatchSecurityProperties();
    security.setBypassMode(true);
    FilesystemObjectStore raw = newRaw(root);
    BatchObjectCryptoService crypto = newCrypto(security);
    EncryptingObjectStore store = new EncryptingObjectStore(raw, crypto, security, KEY_REF);

    assertThatThrownBy(() -> store.getFrom(BUCKET, "any", 0))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("range read");
  }

  @Test
  @DisplayName("存在性判断、大小统计、复制、列举、预签名与删除均委托底层并返回一致结果")
  void shouldDelegateOtherOperations_whenCalled(@TempDir Path root) {
    BatchSecurityProperties security = new BatchSecurityProperties();
    security.setBypassMode(true);
    FilesystemObjectStore raw = newRaw(root);
    BatchObjectCryptoService crypto = newCrypto(security);
    EncryptingObjectStore store = new EncryptingObjectStore(raw, crypto, security, KEY_REF);

    byte[] payload = "x".getBytes(StandardCharsets.UTF_8);
    store.put(BUCKET, "a", new ByteArrayInputStream(payload), payload.length, "x");
    assertThat(store.exists(BUCKET, "a")).isTrue();
    assertThat(store.statSize(BUCKET, "a")).isEqualTo(1);
    store.copy(BUCKET, "a", "b");
    assertThat(store.exists(BUCKET, "b")).isTrue();
    assertThat(store.list(BUCKET, "", null, 10).objects()).hasSize(2);
    String url = store.presign(BUCKET, "a", Duration.ofMinutes(1));
    assertThat(url).contains("b=" + BUCKET);
    store.delete(BUCKET, "a");
    assertThat(store.exists(BUCKET, "a")).isFalse();
  }
}
