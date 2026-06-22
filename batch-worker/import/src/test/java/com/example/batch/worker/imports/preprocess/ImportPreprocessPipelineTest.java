package com.example.batch.worker.imports.preprocess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.batch.worker.imports.domain.ImportPayload;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;

class ImportPreprocessPipelineTest {

  @Test
  void bypassModeShouldSkipChecksumAndCrypto() {
    byte[] out = ImportPreprocessPipeline.run(new byte[0], null, Map.of(), true);
    assertThat(out).isEmpty();
  }

  @Test
  void shouldGunzipWhenCompressTypeGzip() throws Exception {
    byte[] raw = "hello".getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (GZIPOutputStream gos = new GZIPOutputStream(bos)) {
      gos.write(raw);
    }
    byte[] gzipped = bos.toByteArray();
    Map<String, Object> template = Map.of("compress_type", "GZIP");
    byte[] out = ImportPreprocessPipeline.run(gzipped, null, template, true);
    assertThat(out).isEqualTo(raw);
  }

  @Test
  void shouldUntarFirstFileEntryWhenCompressTypeTar() throws Exception {
    byte[] raw = "id,name\n1,alice\n".getBytes(StandardCharsets.UTF_8);
    byte[] tar = buildTar(Map.of("orders.csv", raw));
    Map<String, Object> template = Map.of("compress_type", "TAR");
    byte[] out = ImportPreprocessPipeline.run(tar, null, template, true);
    assertThat(out).isEqualTo(raw);
  }

  @Test
  void shouldUntarGzWhenCompressTypeTarGz() throws Exception {
    byte[] raw = "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8);
    byte[] tar = buildTar(Map.of("data.csv", raw));
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (GZIPOutputStream gos = new GZIPOutputStream(bos)) {
      gos.write(tar);
    }
    Map<String, Object> template = Map.of("compress_type", "TAR_GZ");
    byte[] out = ImportPreprocessPipeline.run(bos.toByteArray(), null, template, true);
    assertThat(out).isEqualTo(raw);
  }

  @Test
  void shouldUntarSelectEntryByNameViaExplicitPipeline() throws Exception {
    // 多文件 tar:显式 entryName 选第二个,验证不是盲取首条
    LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("first.csv", "first".getBytes(StandardCharsets.UTF_8));
    entries.put("second.csv", "second".getBytes(StandardCharsets.UTF_8));
    byte[] tar = buildTar(entries);
    Map<String, Object> step = Map.of("type", "UNTAR", "entryName", "second.csv");
    Map<String, Object> template = Map.of("preprocess_pipeline", java.util.List.of(step));
    byte[] out = ImportPreprocessPipeline.run(tar, null, template, true);
    assertThat(out).isEqualTo("second".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void shouldThrowWhenTarHasNoFileEntry() throws Exception {
    byte[] emptyTar = buildTar(new LinkedHashMap<>());
    Map<String, Object> template = Map.of("compress_type", "TAR");
    assertThatThrownBy(() -> ImportPreprocessPipeline.run(emptyTar, null, template, true))
        .isInstanceOf(ImportPreprocessException.class)
        .hasMessageContaining("no usable entry");
  }

  private static byte[] buildTar(Map<String, byte[]> entries) throws Exception {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (TarArchiveOutputStream tos = new TarArchiveOutputStream(bos)) {
      for (Map.Entry<String, byte[]> e : entries.entrySet()) {
        TarArchiveEntry entry = new TarArchiveEntry(e.getKey());
        entry.setSize(e.getValue().length);
        tos.putArchiveEntry(entry);
        tos.write(e.getValue());
        tos.closeArchiveEntry();
      }
    }
    return bos.toByteArray();
  }

  // ===== AES_GCM_DECRYPT 回归保护 =====
  // 历史异常数据事故:5001/5003 模板配 encrypt_type='AES' 但 import job 没供 aesKeyBase64,
  // worker preprocess 必失败,造成所有任务循环失败 + 异常 job_instance 累积。
  // 本组测试守护:
  //   - bypass=true 下 AES_GCM_DECRYPT 被跳过(本地联调防呆)
  //   - bypass=false + 无 key/iv → 抛 ImportPreprocessException(IMPORT_PREPROCESS_AES_KEY_MISSING)
  //   - 合法 key+iv 解密成功

  @Test
  void aesGcmDecryptShouldBeSkippedInBypassMode() {
    // 编了一坨非法密文,bypass=true 下应直接放过,不会被强制解密失败
    byte[] garbage = "not-real-aes-cipher".getBytes(StandardCharsets.UTF_8);
    Map<String, Object> template = Map.of("encrypt_type", "AES");
    byte[] out = ImportPreprocessPipeline.run(garbage, null, template, true);
    assertThat(out).isEqualTo(garbage);
  }

  @Test
  void aesGcmDecryptShouldFailFastWhenKeyMissingInProdMode() {
    // 异常数据事故的精确回归:bypass=false + AES 但未提供 key/iv → 必抛
    byte[] anyBytes = "anything".getBytes(StandardCharsets.UTF_8);
    Map<String, Object> template = Map.of("encrypt_type", "AES");
    assertThatThrownBy(() -> ImportPreprocessPipeline.run(anyBytes, null, template, false))
        .isInstanceOf(ImportPreprocessException.class)
        .hasMessageContaining("aesKeyBase64");
  }

  @Test
  void aesGcmDecryptShouldSucceedWithKeyAndIvInMetadata() throws Exception {
    // 全链路真实路径:走 metadata.decryptAesKeyBase64 / decryptAesIvBase64
    byte[] plain = "hello world".getBytes(StandardCharsets.UTF_8);
    byte[] key = new byte[16];
    for (int i = 0; i < key.length; i++) key[i] = (byte) i;
    byte[] iv = new byte[12];
    for (int i = 0; i < iv.length; i++) iv[i] = (byte) (i + 7);

    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
    byte[] cipherText = cipher.doFinal(plain);

    Map<String, Object> template = Map.of("encrypt_type", "AES");
    ImportPayload payload =
        new ImportPayload(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            Map.of(
                "decryptAesKeyBase64", Base64.getEncoder().encodeToString(key),
                "decryptAesIvBase64", Base64.getEncoder().encodeToString(iv)));

    byte[] out = ImportPreprocessPipeline.run(cipherText, payload, template, false);
    assertThat(out).isEqualTo(plain);
  }

  @Test
  void unsupportedEncryptTypeShouldThrowInProdMode() {
    // 防御未来 encrypt_type 扩展时 worker 静默吃掉
    byte[] anyBytes = "x".getBytes(StandardCharsets.UTF_8);
    Map<String, Object> template = Map.of("encrypt_type", "FUTURE_ALGO");
    assertThatThrownBy(() -> ImportPreprocessPipeline.run(anyBytes, null, template, false))
        .isInstanceOf(ImportPreprocessException.class);
  }

  @Test
  void encryptTypeNoneShouldPassThroughUnchanged() {
    byte[] raw = "plain".getBytes(StandardCharsets.UTF_8);
    Map<String, Object> template = Map.of("encrypt_type", "NONE");
    // bypass=true 跳过 implicit checksum 校验,直接验证 NONE 不被当成解密算法
    byte[] out = ImportPreprocessPipeline.run(raw, null, template, true);
    assertThat(out).isEqualTo(raw);
  }

  @Test
  void verifyDigestShouldPassWhenChecksumMatches() throws Exception {
    byte[] raw = "abc".getBytes(StandardCharsets.UTF_8);
    java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
    String expectedHex = HexFormat.of().formatHex(md.digest(raw));
    Map<String, Object> step =
        Map.of("type", "VERIFY_DIGEST", "algorithm", "SHA-256", "expectedHex", expectedHex);
    Map<String, Object> template = Map.of("preprocess_pipeline", java.util.List.of(step));

    byte[] out = ImportPreprocessPipeline.run(raw, null, template, false);
    assertThat(out).isEqualTo(raw);
  }

  @Test
  void verifyDigestShouldThrowWhenChecksumMismatches() {
    byte[] raw = "abc".getBytes(StandardCharsets.UTF_8);
    Map<String, Object> step =
        Map.of(
            "type", "VERIFY_DIGEST", "algorithm", "SHA-256", "expectedHex", "deadbeef".repeat(8));
    Map<String, Object> template = Map.of("preprocess_pipeline", java.util.List.of(step));
    assertThatThrownBy(() -> ImportPreprocessPipeline.run(raw, null, template, false))
        .isInstanceOf(ImportPreprocessException.class);
  }
}
