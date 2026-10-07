package io.github.pinpols.batch.worker.imports.preprocess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.worker.imports.domain.ImportPayload;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("导入预处理管道单测:压缩解包,加密解密,摘要校验与旁路模式语义")
class ImportPreprocessPipelineTest {

  @Test
  @DisplayName("旁路模式开启时跳过摘要校验与加解密,空输入仍为空输出")
  void shouldSkipChecksumAndCrypto_whenBypassModeEnabled() {
    byte[] out = ImportPreprocessPipeline.run(new byte[0], null, Map.of(), true);
    assertThat(out).isEmpty();
  }

  @Test
  @DisplayName("压缩类型为 gzip 时先解压,输出等于原始内容")
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
  @DisplayName("压缩类型为 tar 包时,解出首个文件条目")
  void shouldUntarFirstFileEntryWhenCompressTypeTar() throws Exception {
    byte[] raw = """
        id,name
        1,alice
        """.getBytes(StandardCharsets.UTF_8);
    byte[] tar = buildTar(Map.of("orders.csv", raw));
    Map<String, Object> template = Map.of("compress_type", "TAR");
    byte[] out = ImportPreprocessPipeline.run(tar, null, template, true);
    assertThat(out).isEqualTo(raw);
  }

  @Test
  @DisplayName("压缩类型为 tar 加点 gzip 时,先解压再解包")
  void shouldUntarGzWhenCompressTypeTarGz() throws Exception {
    byte[] raw = """
        a,b
        1,2
        """.getBytes(StandardCharsets.UTF_8);
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
  @DisplayName("显式管道指定条目名时,解出对应文件内容")
  void shouldUntarSelectEntryByNameViaExplicitPipeline() throws Exception {
    // 多文件 tar:显式 entryName 选第二个,验证不是盲取首条
    LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("first.csv", "first".getBytes(StandardCharsets.UTF_8));
    entries.put("second.csv", "second".getBytes(StandardCharsets.UTF_8));
    byte[] tar = buildTar(entries);
    Map<String, Object> step =
        Map.of("type", ImportPreprocessStepTypes.UNTAR, "entryName", "second.csv");
    Map<String, Object> template = Map.of("preprocess_pipeline", List.of(step));
    byte[] out = ImportPreprocessPipeline.run(tar, null, template, true);
    assertThat(out).isEqualTo("second".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("包内没有文件条目时抛异常")
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
  @DisplayName("旁路模式开启时不尝试解密,密文原样输出")
  void shouldSkipDecryption_whenBypassModeEnabled() {
    // 编了一坨非法密文,bypass=true 下应直接放过,不会被强制解密失败
    byte[] garbage = "not-real-aes-cipher".getBytes(StandardCharsets.UTF_8);
    Map<String, Object> template = Map.of("encrypt_type", "AES");
    byte[] out = ImportPreprocessPipeline.run(garbage, null, template, true);
    assertThat(out).isEqualTo(garbage);
  }

  @Test
  @DisplayName("生产模式下缺少解密密钥时立即失败")
  void shouldFailFast_whenDecryptionKeyMissingInProdMode() {
    // 异常数据事故的精确回归:bypass=false + AES 但未提供 key/iv → 必抛
    byte[] anyBytes = "anything".getBytes(StandardCharsets.UTF_8);
    Map<String, Object> template = Map.of("encrypt_type", "AES");
    assertThatThrownBy(() -> ImportPreprocessPipeline.run(anyBytes, null, template, false))
        .isInstanceOf(ImportPreprocessException.class)
        .hasMessageContaining("aesKeyBase64");
  }

  @Test
  @DisplayName("载荷元数据提供密钥与初始向量时解密成功,还原明文")
  void shouldDecrypt_whenKeyAndIvProvidedInMetadata() throws Exception {
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
    ImportPayload payload = new ImportPayload(
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
  @DisplayName("生产模式下加密类型不受支持时直接抛异常")
  void shouldThrow_whenEncryptTypeUnsupportedInProdMode() {
    // 防御未来 encrypt_type 扩展时 worker 静默吃掉
    byte[] anyBytes = "x".getBytes(StandardCharsets.UTF_8);
    Map<String, Object> template = Map.of("encrypt_type", "FUTURE_ALGO");
    assertThatThrownBy(() -> ImportPreprocessPipeline.run(anyBytes, null, template, false))
        .isInstanceOf(ImportPreprocessException.class);
  }

  @Test
  @DisplayName("加密类型为不加密时,内容原样通过")
  void shouldPassThrough_whenEncryptTypeIsNone() {
    byte[] raw = "plain".getBytes(StandardCharsets.UTF_8);
    Map<String, Object> template = Map.of("encrypt_type", "NONE");
    // bypass=true 跳过 implicit checksum 校验,直接验证 NONE 不被当成解密算法
    byte[] out = ImportPreprocessPipeline.run(raw, null, template, true);
    assertThat(out).isEqualTo(raw);
  }

  @Test
  @DisplayName("摘要与声明校验和一致时校验通过,内容原样输出")
  void shouldPassDigestVerification_whenChecksumMatches() throws Exception {
    byte[] raw = "abc".getBytes(StandardCharsets.UTF_8);
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    String expectedHex = HexFormat.of().formatHex(md.digest(raw));
    Map<String, Object> step = Map.of(
        "type",
        ImportPreprocessStepTypes.VERIFY_DIGEST,
        "algorithm",
        "SHA-256",
        "expectedHex",
        expectedHex);
    Map<String, Object> template = Map.of("preprocess_pipeline", List.of(step));

    byte[] out = ImportPreprocessPipeline.run(raw, null, template, false);
    assertThat(out).isEqualTo(raw);
  }

  @Test
  @DisplayName("摘要与声明校验和不一致时抛异常")
  void shouldThrow_whenChecksumMismatches() {
    byte[] raw = "abc".getBytes(StandardCharsets.UTF_8);
    Map<String, Object> step = Map.of(
        "type",
        ImportPreprocessStepTypes.VERIFY_DIGEST,
        "algorithm",
        "SHA-256",
        "expectedHex",
        "deadbeef".repeat(8));
    Map<String, Object> template = Map.of("preprocess_pipeline", List.of(step));
    assertThatThrownBy(() -> ImportPreprocessPipeline.run(raw, null, template, false))
        .isInstanceOf(ImportPreprocessException.class);
  }

  @Test
  @DisplayName("隐式校验和针对变换前的原始字节校验,再执行后续变换")
  void shouldVerifyRawBytes_whenImplicitChecksumConfigured() throws Exception {
    byte[] plain = "hello".getBytes(StandardCharsets.UTF_8);
    byte[] gz = gzip(plain);
    String rawChecksum =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(gz));
    ImportPayload payload = new ImportPayload(
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        "SHA-256",
        rawChecksum,
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
        Map.of());
    Map<String, Object> template = Map.of("compress_type", "GZIP");

    byte[] out = ImportPreprocessPipeline.run(gz, payload, template, false);

    assertThat(out).isEqualTo(plain);
  }

  @Test
  @DisplayName("管道以 JSON 描述时正常解析,并跳过未写类型的步骤")
  void shouldParseJsonPipelineAndSkipBlankStepType() {
    byte[] raw = "hello".getBytes(StandardCharsets.UTF_8);
    String pipeline = """
        [
          {"type":"   "},
          {"type":"CHARSET_TRANSCODE","fromCharset":"UTF-8","toCharset":"UTF-16BE"}
        ]
        """;
    Map<String, Object> template = Map.of("preprocess_pipeline", pipeline);

    byte[] out = ImportPreprocessPipeline.run(raw, null, template, true);

    assertThat(new String(out, StandardCharsets.UTF_16BE)).isEqualTo("hello");
  }

  @Test
  @DisplayName("转码前剥离字节序标记,再按目标编码转码")
  void charsetTranscode_shouldStripUtf8BomBeforeTranscoding() {
    // UTF-8 BOM + 内容，转码到 UTF-16BE 后 BOM 不应出现在业务内容里
    byte[] bom = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    byte[] raw = concat(bom, "客户".getBytes(StandardCharsets.UTF_8));
    String pipeline = """
        [{"type":"CHARSET_TRANSCODE","fromCharset":"UTF-8","toCharset":"UTF-16BE"}]
        """;
    Map<String, Object> template = Map.of("preprocess_pipeline", pipeline);

    byte[] out = ImportPreprocessPipeline.run(raw, null, template, true);

    assertThat(new String(out, StandardCharsets.UTF_16BE)).isEqualTo("客户");
  }

  private static byte[] concat(byte[] first, byte[] second) {
    byte[] result = new byte[first.length + second.length];
    System.arraycopy(first, 0, result, 0, first.length);
    System.arraycopy(second, 0, result, first.length, second.length);
    return result;
  }

  private static byte[] gzip(byte[] payload) throws Exception {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (GZIPOutputStream gos = new GZIPOutputStream(bos)) {
      gos.write(payload);
    }
    return bos.toByteArray();
  }
}
