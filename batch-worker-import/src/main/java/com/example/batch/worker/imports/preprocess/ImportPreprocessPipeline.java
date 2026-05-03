package com.example.batch.worker.imports.preprocess;

import com.example.batch.common.utils.EncodingUtils;
import com.example.batch.common.utils.Texts;
import com.example.batch.worker.imports.domain.ImportPayload;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 有序二进制预处理管道（工具类，不可实例化）：对原始文件字节按步骤顺序依次执行解压、解密、摘要校验和字符集转码。
 *
 * <p><b>步骤解析规则</b>：优先使用模板配置中的 {@code preprocess_pipeline}（JSON 数组）； 不存在时根据 {@code
 * compress_type}（ZIP / GZIP）和 {@code encrypt_type}（AES）隐式推导步骤。 其他加密类型在隐式模式下抛出 {@code
 * IMPORT_PREPROCESS_ENCRYPT_UNSUPPORTED}。
 *
 * <p><b>支持步骤类型</b>：
 *
 * <ul>
 *   <li>{@code UNZIP} — 解 ZIP，支持 {@code entryName} 指定条目
 *   <li>{@code GUNZIP} — 解 GZIP
 *   <li>{@code AES_GCM_DECRYPT} — AES/GCM/NoPadding 解密，需提供 {@code aesKeyBase64} / {@code
 *       aesIvBase64}
 *   <li>{@code VERIFY_DIGEST} — SHA-256 / MD5 摘要校验，期望值来自步骤配置或 {@link ImportPayload#checksumValue()}
 *   <li>{@code VERIFY_RSA_SHA256} — RSA 签名验证，需提供 PEM 公钥和 Base64 签名
 *   <li>{@code CHARSET_TRANSCODE} — 字节编码转换（{@code fromCharset} / {@code toCharset}）
 * </ul>
 *
 * <p>{@code bypassMode=true} 时跳过 AES 解密、摘要校验和 RSA 验签，便于测试环境无密钥运行。
 */
public final class ImportPreprocessPipeline {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String KEY_TYPE = "type";
  private static final String POLICY_NONE = "NONE";
  private static final String EMPTY = "";

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

  /**
   * 隐式步骤推导表：{@code compress_type} / {@code encrypt_type} 的 UPPERCASE 值 → 对应 preprocess step。
   * 扩展新的压缩/加密算法时，在表里新增一行即可，避免散落的 if-else。 {@code encrypt_type=NONE} 视为无加密，在调用端直接跳过；其他未注册的加密类型在非
   * bypass 模式下拒收。
   */
  private static final Map<String, String> IMPLICIT_COMPRESS_STEPS =
      Map.of("ZIP", "UNZIP", "GZIP", "GUNZIP");

  private static final Map<String, String> IMPLICIT_ENCRYPT_STEPS =
      Map.of("AES", "AES_GCM_DECRYPT");

  private ImportPreprocessPipeline() {}

  public static byte[] run(byte[] input, ImportPayload payload, Map<String, Object> template) {
    return run(input, payload, template, false);
  }

  public static byte[] run(
      byte[] input, ImportPayload payload, Map<String, Object> template, boolean bypassMode) {
    try {
      if (input == null) {
        input = new byte[0];
      }
      List<Map<String, Object>> steps = resolveSteps(template, bypassMode);
      byte[] current = input;
      boolean digestVerifiedInPipeline = false;
      for (Map<String, Object> step : steps) {
        String type = stringProp(step, KEY_TYPE);
        if (!Texts.hasText(type)) {
          continue;
        }
        switch (type.toUpperCase(Locale.ROOT)) {
          case "UNZIP" -> current = unzip(current, step, payload);
          case "GUNZIP" -> current = gunzip(current);
          case "AES_GCM_DECRYPT" -> {
            if (!bypassMode) {
              current = aesGcmDecrypt(current, step, payload);
            }
          }
          case "VERIFY_DIGEST" -> {
            if (!bypassMode) {
              verifyDigest(current, step, payload, template);
              digestVerifiedInPipeline = true;
            }
          }
          case "VERIFY_RSA_SHA256" -> {
            if (!bypassMode) {
              verifyRsaSha256(current, step, payload);
            }
          }
          case "CHARSET_TRANSCODE" -> current = charsetTranscode(current, step);
          default ->
              throw new ImportPreprocessException(
                  "IMPORT_PREPROCESS_UNKNOWN_STEP", "unknown preprocess step type: " + type);
        }
      }
      if (!bypassMode && !digestVerifiedInPipeline) {
        verifyImplicitChecksum(current, payload, template);
      }
      return current;
    } catch (ImportPreprocessException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new ImportPreprocessException("IMPORT_PREPROCESS_FAILED", ex.getMessage(), ex);
    }
  }

  private static List<Map<String, Object>> resolveSteps(
      Map<String, Object> template, boolean bypassMode) {
    Object raw = template == null ? null : template.get("preprocess_pipeline");
    if (raw != null) {
      List<Map<String, Object>> parsed = parsePipeline(raw);
      if (!parsed.isEmpty()) {
        return parsed;
      }
    }
    List<Map<String, Object>> implicit = new ArrayList<>();
    appendImplicitStep(implicit, IMPLICIT_COMPRESS_STEPS, stringProp(template, "compress_type"));
    String enc = stringProp(template, "encrypt_type");
    if (!appendImplicitStep(implicit, IMPLICIT_ENCRYPT_STEPS, enc)
        && Texts.hasText(enc)
        && !POLICY_NONE.equalsIgnoreCase(enc)
        && !bypassMode) {
      throw new ImportPreprocessException(
          "IMPORT_PREPROCESS_ENCRYPT_UNSUPPORTED",
          "encrypt_type "
              + enc
              + " is not supported in implicit mode; use preprocess_pipeline or NONE");
    }
    return implicit;
  }

  /** 按查表结果追加隐式 step；命中返回 true，未命中（含空值）返回 false 交由调用方决定是否报错。 */
  private static boolean appendImplicitStep(
      List<Map<String, Object>> implicit, Map<String, String> lookup, String rawType) {
    if (!Texts.hasText(rawType)) {
      return false;
    }
    String stepType = lookup.get(rawType.trim().toUpperCase(Locale.ROOT));
    if (stepType == null) {
      return false;
    }
    implicit.add(new LinkedHashMap<>(Map.of(KEY_TYPE, stepType)));
    return true;
  }

  private static List<Map<String, Object>> parsePipeline(Object raw) {
    if (raw instanceof List<?> list) {
      List<Map<String, Object>> out = new ArrayList<>();
      for (Object item : list) {
        if (item instanceof Map<?, ?> map) {
          Map<String, Object> copy = new LinkedHashMap<>();
          map.forEach((k, v) -> copy.put(String.valueOf(k), v));
          out.add(copy);
        }
      }
      return out;
    }
    if (raw instanceof String text && Texts.hasText(text)) {
      try {
        return OBJECT_MAPPER.readValue(text, new TypeReference<List<Map<String, Object>>>() {});
      } catch (Exception ex) {
        throw new ImportPreprocessException(
            "IMPORT_PREPROCESS_PIPELINE_JSON", "invalid preprocess_pipeline json", ex);
      }
    }
    return List.of();
  }

  private static byte[] unzip(byte[] input, Map<String, Object> step, ImportPayload payload) {
    String entryName = stringProp(step, "entryName");
    if (!Texts.hasText(entryName) && payload != null && payload.metadata() != null) {
      Object v = payload.metadata().get("zipEntryName");
      if (v != null && Texts.hasText(String.valueOf(v))) {
        entryName = String.valueOf(v);
      }
    }
    try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(input))) {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
        if (entry.isDirectory()) {
          continue;
        }
        if (Texts.hasText(entryName) && !entryName.equals(entry.getName())) {
          continue;
        }
        return boundedReadAll(zis, input.length, "UNZIP");
      }
    } catch (IOException ex) {
      throw new ImportPreprocessException("IMPORT_PREPROCESS_UNZIP_FAILED", ex.getMessage(), ex);
    }
    throw new ImportPreprocessException(
        "IMPORT_PREPROCESS_UNZIP_EMPTY", "zip archive has no usable entry");
  }

  private static byte[] gunzip(byte[] input) {
    try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(input))) {
      return boundedReadAll(gis, input.length, "GUNZIP");
    } catch (IOException ex) {
      throw new ImportPreprocessException("IMPORT_PREPROCESS_GUNZIP_FAILED", ex.getMessage(), ex);
    }
  }

  // ── 解压尺寸闸（防 zip bomb / 压缩炸弹）────────────────────────────────
  // 解压后字节同时受两个上限制约，取最小值：
  //   1) 绝对上限 MAX_DECOMPRESS_BYTES（默认 256 MiB，防单文件过大拖死堆）
  //   2) 相对输入的膨胀倍数 MAX_DECOMPRESS_RATIO（默认 50x，典型文本压缩 3-10x，50x 就是异常）
  // 超过即抛 IMPORT_PREPROCESS_DECOMPRESS_TOO_LARGE，文件拒收而不是静默把堆写爆。
  //
  // 2026-05-03 ⚠1: 默认从 1 GiB 下调到 256 MiB. 之前 1 GiB × 6 并发 task = 6 GiB 堆压, 真实业务大文件
  // 需要更大上限可通过 -Dbatch.worker.import.max-decompress-bytes 显式提高.
  private static final long MAX_DECOMPRESS_BYTES =
      Long.getLong("batch.worker.import.max-decompress-bytes", 256L * 1024 * 1024);
  private static final int MAX_DECOMPRESS_RATIO =
      Integer.getInteger("batch.worker.import.max-decompress-ratio", 50);

  private static byte[] boundedReadAll(InputStream in, int inputLen, String stepLabel)
      throws IOException {
    long capRatio = (long) Math.max(inputLen, 1) * MAX_DECOMPRESS_RATIO;
    long cap = Math.min(MAX_DECOMPRESS_BYTES, capRatio);
    ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.min(inputLen * 4, 1024 * 1024));
    byte[] buf = new byte[8192];
    long total = 0;
    int n;
    while ((n = in.read(buf)) > 0) {
      total += n;
      if (total > cap) {
        throw new ImportPreprocessException(
            "IMPORT_PREPROCESS_DECOMPRESS_TOO_LARGE",
            stepLabel
                + " output "
                + total
                + " bytes exceeds cap "
                + cap
                + " (input="
                + inputLen
                + ", ratio="
                + MAX_DECOMPRESS_RATIO
                + ", absMax="
                + MAX_DECOMPRESS_BYTES
                + ")");
      }
      baos.write(buf, 0, n);
    }
    return baos.toByteArray();
  }

  private static byte[] aesGcmDecrypt(byte[] input, Map<String, Object> step, ImportPayload payload)
      throws Exception {
    Map<String, Object> meta = payload != null ? payload.metadata() : Map.of();
    String keyB64 =
        firstNonBlank(stringProp(step, "aesKeyBase64"), metaString(meta, "decryptAesKeyBase64"));
    String ivB64 =
        firstNonBlank(stringProp(step, "aesIvBase64"), metaString(meta, "decryptAesIvBase64"));
    if (!Texts.hasText(keyB64) || !Texts.hasText(ivB64)) {
      throw new ImportPreprocessException(
          "IMPORT_PREPROCESS_AES_KEY_MISSING",
          "AES_GCM_DECRYPT requires aesKeyBase64 and aesIvBase64 on the step or metadata"
              + " decryptAesKeyBase64 / decryptAesIvBase64");
    }
    byte[] keyBytes = Base64.getDecoder().decode(keyB64.trim());
    byte[] ivBytes = Base64.getDecoder().decode(ivB64.trim());
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE,
        new SecretKeySpec(keyBytes, "AES"),
        new GCMParameterSpec(128, ivBytes));
    return cipher.doFinal(input);
  }

  private static void verifyDigest(
      byte[] input, Map<String, Object> step, ImportPayload payload, Map<String, Object> template)
      throws Exception {
    String algorithm =
        firstNonBlank(stringProp(step, "algorithm"), digestAlgorithm(template, payload));
    String expected = firstNonBlank(stringProp(step, "expectedHex"), checksumExpected(payload));
    if (!Texts.hasText(expected)) {
      throw new ImportPreprocessException(
          "IMPORT_PREPROCESS_DIGEST_EXPECTED_MISSING",
          "VERIFY_DIGEST requires expectedHex or ImportPayload.checksumValue");
    }
    MessageDigest digest = MessageDigest.getInstance(algorithm);
    byte[] hash = digest.digest(input);
    String actual = HexFormat.of().formatHex(hash);
    if (!actual.equalsIgnoreCase(expected.trim().replace(" ", EMPTY))) {
      throw new ImportPreprocessException(
          "IMPORT_PREPROCESS_DIGEST_MISMATCH", "digest mismatch for " + algorithm);
    }
  }

  private static void verifyImplicitChecksum(
      byte[] input, ImportPayload payload, Map<String, Object> template) throws Exception {
    String algorithm = digestAlgorithm(template, payload);
    if (POLICY_NONE.equalsIgnoreCase(algorithm)) {
      return;
    }
    String expected = checksumExpected(payload);
    if (!Texts.hasText(expected)) {
      return;
    }
    MessageDigest digest = MessageDigest.getInstance(algorithm);
    byte[] hash = digest.digest(input);
    String actual = HexFormat.of().formatHex(hash);
    if (!actual.equalsIgnoreCase(expected.trim().replace(" ", EMPTY))) {
      throw new ImportPreprocessException(
          "IMPORT_PREPROCESS_CHECKSUM_MISMATCH", "checksum mismatch for " + algorithm);
    }
  }

  private static String digestAlgorithm(Map<String, Object> template, ImportPayload payload) {
    if (payload != null
        && Texts.hasText(payload.checksumType())
        && !POLICY_NONE.equalsIgnoreCase(payload.checksumType())) {
      return normalizeDigestName(payload.checksumType());
    }
    String fromTemplate = stringProp(template, "checksum_type");
    if (Texts.hasText(fromTemplate) && !POLICY_NONE.equalsIgnoreCase(fromTemplate)) {
      return normalizeDigestName(fromTemplate);
    }
    return POLICY_NONE;
  }

  private static String normalizeDigestName(String raw) {
    if (!Texts.hasText(raw)) {
      return POLICY_NONE;
    }
    String upper = raw.trim().toUpperCase(Locale.ROOT);
    if ("MD5".equals(upper)) {
      return "MD5";
    }
    if ("SHA-256".equals(upper) || "SHA256".equals(upper)) {
      return "SHA-256";
    }
    return upper;
  }

  private static String checksumExpected(ImportPayload payload) {
    if (payload == null || !Texts.hasText(payload.checksumValue())) {
      return null;
    }
    return payload.checksumValue().trim();
  }

  private static void verifyRsaSha256(byte[] input, Map<String, Object> step, ImportPayload payload)
      throws Exception {
    String pem = stringProp(step, "publicKeyPem");
    String signatureB64 =
        firstNonBlank(
            stringProp(step, "signatureBase64"),
            metaString(payload == null ? Map.of() : payload.metadata(), "signatureBase64"));
    if (!Texts.hasText(pem) || !Texts.hasText(signatureB64)) {
      throw new ImportPreprocessException(
          "IMPORT_PREPROCESS_RSA_CONFIG_MISSING",
          "VERIFY_RSA_SHA256 requires publicKeyPem and signatureBase64 (step or"
              + " metadata.signatureBase64)");
    }
    PublicKey publicKey = readPublicKeyFromPem(pem);
    Signature signature = Signature.getInstance("SHA256withRSA");
    signature.initVerify(publicKey);
    signature.update(input);
    byte[] signBytes = Base64.getDecoder().decode(signatureB64.trim());
    if (!signature.verify(signBytes)) {
      throw new ImportPreprocessException(
          "IMPORT_PREPROCESS_RSA_VERIFY_FAILED", "RSA signature verification failed");
    }
  }

  private static PublicKey readPublicKeyFromPem(String pem) throws Exception {
    String stripped =
        pem.replace("-----BEGIN PUBLIC KEY-----", EMPTY)
            .replace("-----END PUBLIC KEY-----", EMPTY)
            .replaceAll("\\s", EMPTY);
    byte[] decoded = Base64.getDecoder().decode(stripped);
    X509EncodedKeySpec spec = new X509EncodedKeySpec(decoded);
    return KeyFactory.getInstance("RSA").generatePublic(spec);
  }

  /**
   * A-3.13：CHARSET_TRANSCODE 输出大小硬上限。GBK/GB18030 → UTF-8 时字节可能膨胀到 2-3×， 无上限就能让上游构造一个接近 OOM 阈值的输入把整个
   * worker 拖死。
   *
   * <p>默认 = {@code max(inputLen × 2 + 1MB, 16MB)}；模板可通过 step.{@code outputSizeCap} 覆盖。超限抛 {@link
   * IllegalArgumentException}，由 ReceiveStep / PreprocessStep 的 catch 兜底 落
   * file_record.reason_message。
   */
  private static final long CHARSET_TRANSCODE_MIN_CAP_BYTES = 16L * 1024L * 1024L;

  private static byte[] charsetTranscode(byte[] input, Map<String, Object> step) {
    String from = firstNonBlank(stringProp(step, "fromCharset"), EncodingUtils.UTF_8);
    String to = firstNonBlank(stringProp(step, "toCharset"), EncodingUtils.UTF_8);
    Charset fromCs = EncodingUtils.resolve(from);
    Charset toCs = EncodingUtils.resolve(to);
    long computedCap =
        Math.max((long) input.length * 2L + 1_048_576L, CHARSET_TRANSCODE_MIN_CAP_BYTES);
    long cap = parseLong(stringProp(step, "outputSizeCap"), computedCap);
    // ⚠3 (2026-05-03): 之前 new String(input, fromCs) 把整个文件物化为 UTF-16 String, 100 MB 输入 = 200 MB
    // 中间堆峰 (input byte[] + UTF-16 String) + 100 MB 输出 byte[] = 总 300 MB+ 峰值. 现在改 reader/writer
    // chunk 转码, 中间 buffer 仅 8 KiB; 输出 BAOS 同时检 cap 触发即抛, 不让超量数据继续累积.
    java.io.ByteArrayOutputStream out =
        new java.io.ByteArrayOutputStream(Math.min(input.length, 1 << 16));
    try (java.io.Reader reader =
            new java.io.InputStreamReader(new ByteArrayInputStream(input), fromCs);
        java.io.Writer writer = new java.io.OutputStreamWriter(out, toCs)) {
      char[] buf = new char[8192];
      int n;
      while ((n = reader.read(buf)) > 0) {
        writer.write(buf, 0, n);
        // 中途即检查 cap, 避免超量字节先被 transcode 出来再爆
        if (out.size() > cap) {
          throw new IllegalArgumentException(
              "CHARSET_TRANSCODE output exceeds cap: inputBytes="
                  + input.length
                  + ", outputBytes>="
                  + out.size()
                  + ", cap="
                  + cap
                  + " (from="
                  + fromCs
                  + ", to="
                  + toCs
                  + ")");
        }
      }
      writer.flush();
    } catch (IOException ex) {
      throw new IllegalArgumentException(
          "CHARSET_TRANSCODE failed: "
              + ex.getMessage()
              + " (from="
              + fromCs
              + ", to="
              + toCs
              + ")",
          ex);
    }
    return out.toByteArray();
  }

  private static long parseLong(String raw, long fallback) {
    if (!Texts.hasText(raw)) {
      return fallback;
    }
    try {
      long parsed = Long.parseLong(raw.trim());
      return parsed > 0 ? parsed : fallback;
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static String stringProp(Map<String, Object> map, String key) {
    if (map == null || key == null) {
      return null;
    }
    Object v = map.get(key);
    return v == null ? null : String.valueOf(v);
  }

  private static String metaString(Map<String, Object> meta, String key) {
    if (meta == null) {
      return null;
    }
    Object v = meta.get(key);
    return v == null ? null : String.valueOf(v);
  }

  private static String firstNonBlank(String a, String b) {
    if (Texts.hasText(a)) {
      return a;
    }
    if (Texts.hasText(b)) {
      return b;
    }
    return null;
  }
}
