package io.github.pinpols.batch.common.storage.ingress;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.config.S3StorageProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.springframework.core.env.Environment;

/** 入口归属与扫描协调使用相同的物理存储及前缀身份。 */
public final class ImportIngressOwnershipIdentity {

  private ImportIngressOwnershipIdentity() {}

  /** 与既有扫描协调范围使用相同字段、排序和编码，确保升级后身份不变。 */
  public static String storageId(
      Environment environment, S3StorageProperties properties, ObjectMapper objectMapper) {
    String namespace = environment.getProperty("batch.kafka.namespace", "").trim();
    String backend =
        environment.getProperty("batch.storage.backend", "s3").trim().toLowerCase(Locale.ROOT);
    String storage = "filesystem".equals(backend)
        ? Path.of(environment.getProperty("batch.storage.filesystem.root", "."))
            .toAbsolutePath()
            .normalize()
            .toString()
        : Objects.requireNonNullElse(properties.getEndpoint(), "").trim().replaceAll("/+$", "");
    Map<String, Object> identity = new TreeMap<>();
    identity.put("namespace", namespace);
    identity.put("backend", backend);
    identity.put("storage", storage);
    identity.put("bucket", properties.getBucket());
    return digest(identity, objectMapper);
  }

  public static String scopeId(String storageId, String prefix, ObjectMapper objectMapper) {
    Map<String, Object> identity = new TreeMap<>();
    identity.put("storage", storageId);
    identity.put("prefix", Objects.requireNonNullElse(prefix, ""));
    return digest(identity, objectMapper);
  }

  private static String digest(Map<String, Object> value, ObjectMapper objectMapper) {
    try {
      byte[] encoded = objectMapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
    } catch (IOException | NoSuchAlgorithmException exception) {
      throw new IllegalStateException("无法计算入站扫描范围身份", exception);
    }
  }
}
