package com.example.batch.worker.imports.preprocess;

import com.example.batch.common.utils.EncodingUtils;
import com.example.batch.worker.imports.domain.ImportPayload;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
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
import org.springframework.util.StringUtils;

/**
 * 有序二进制预处理管道（工具类，不可实例化）：对原始文件字节按步骤顺序依次执行解压、解密、摘要校验和字符集转码。
 *
 * <p><b>步骤解析规则</b>：优先使用模板配置中的 {@code preprocess_pipeline}（JSON 数组）；
 * 不存在时根据 {@code compress_type}（ZIP / GZIP）和 {@code encrypt_type}（AES）隐式推导步骤。
 * 其他加密类型在隐式模式下抛出 {@code IMPORT_PREPROCESS_ENCRYPT_UNSUPPORTED}。
 *
 * <p><b>支持步骤类型</b>：
 * <ul>
 *   <li>{@code UNZIP} — 解 ZIP，支持 {@code entryName} 指定条目
 *   <li>{@code GUNZIP} — 解 GZIP
 *   <li>{@code AES_GCM_DECRYPT} — AES/GCM/NoPadding 解密，需提供 {@code aesKeyBase64} / {@code aesIvBase64}
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
        if (!StringUtils.hasText(type)) {
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
    String compress = stringProp(template, "compress_type");
    if ("ZIP".equalsIgnoreCase(compress)) {
      implicit.add(new LinkedHashMap<>(Map.of(KEY_TYPE, "UNZIP")));
    } else if ("GZIP".equalsIgnoreCase(compress)) {
      implicit.add(new LinkedHashMap<>(Map.of(KEY_TYPE, "GUNZIP")));
    }
    String enc = stringProp(template, "encrypt_type");
    if ("AES".equalsIgnoreCase(enc)) {
      implicit.add(new LinkedHashMap<>(Map.of(KEY_TYPE, "AES_GCM_DECRYPT")));
    } else if (StringUtils.hasText(enc) && !POLICY_NONE.equalsIgnoreCase(enc) && !bypassMode) {
      throw new ImportPreprocessException(
          "IMPORT_PREPROCESS_ENCRYPT_UNSUPPORTED",
          "encrypt_type "
              + enc
              + " is not supported in implicit mode; use preprocess_pipeline or NONE");
    }
    return implicit;
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
    if (raw instanceof String text && StringUtils.hasText(text)) {
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
    if (!StringUtils.hasText(entryName) && payload != null && payload.metadata() != null) {
      Object v = payload.metadata().get("zipEntryName");
      if (v != null && StringUtils.hasText(String.valueOf(v))) {
        entryName = String.valueOf(v);
      }
    }
    try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(input))) {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
        if (entry.isDirectory()) {
          continue;
        }
        if (StringUtils.hasText(entryName) && !entryName.equals(entry.getName())) {
          continue;
        }
        return zis.readAllBytes();
      }
    } catch (IOException ex) {
      throw new ImportPreprocessException("IMPORT_PREPROCESS_UNZIP_FAILED", ex.getMessage(), ex);
    }
    throw new ImportPreprocessException(
        "IMPORT_PREPROCESS_UNZIP_EMPTY", "zip archive has no usable entry");
  }

  private static byte[] gunzip(byte[] input) {
    try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(input))) {
      return gis.readAllBytes();
    } catch (IOException ex) {
      throw new ImportPreprocessException("IMPORT_PREPROCESS_GUNZIP_FAILED", ex.getMessage(), ex);
    }
  }

  private static byte[] aesGcmDecrypt(byte[] input, Map<String, Object> step, ImportPayload payload)
      throws Exception {
    Map<String, Object> meta = payload != null ? payload.metadata() : Map.of();
    String keyB64 =
        firstNonBlank(stringProp(step, "aesKeyBase64"), metaString(meta, "decryptAesKeyBase64"));
    String ivB64 =
        firstNonBlank(stringProp(step, "aesIvBase64"), metaString(meta, "decryptAesIvBase64"));
    if (!StringUtils.hasText(keyB64) || !StringUtils.hasText(ivB64)) {
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
    if (!StringUtils.hasText(expected)) {
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
    if (!StringUtils.hasText(expected)) {
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
        && StringUtils.hasText(payload.checksumType())
        && !POLICY_NONE.equalsIgnoreCase(payload.checksumType())) {
      return normalizeDigestName(payload.checksumType());
    }
    String fromTemplate = stringProp(template, "checksum_type");
    if (StringUtils.hasText(fromTemplate) && !POLICY_NONE.equalsIgnoreCase(fromTemplate)) {
      return normalizeDigestName(fromTemplate);
    }
    return POLICY_NONE;
  }

  private static String normalizeDigestName(String raw) {
    if (!StringUtils.hasText(raw)) {
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
    if (payload == null || !StringUtils.hasText(payload.checksumValue())) {
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
    if (!StringUtils.hasText(pem) || !StringUtils.hasText(signatureB64)) {
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

  private static byte[] charsetTranscode(byte[] input, Map<String, Object> step) {
    String from = firstNonBlank(stringProp(step, "fromCharset"), "UTF-8");
    String to = firstNonBlank(stringProp(step, "toCharset"), "UTF-8");
    Charset fromCs = EncodingUtils.resolve(from);
    Charset toCs = EncodingUtils.resolve(to);
    String text = new String(input, fromCs);
    return text.getBytes(toCs);
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
    if (StringUtils.hasText(a)) {
      return a;
    }
    if (StringUtils.hasText(b)) {
      return b;
    }
    return null;
  }
}
