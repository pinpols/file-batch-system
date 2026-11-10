package io.github.pinpols.batch.common.storage.ingress;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.config.FilesystemStorageProperties;
import io.github.pinpols.batch.common.config.KafkaNamespaceProperties;
import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.config.StorageBackendProperties;
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
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/** 入口归属与扫描协调使用相同的物理存储及前缀身份。 */
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties({
  KafkaNamespaceProperties.class,
  StorageBackendProperties.class,
  FilesystemStorageProperties.class
})
public final class ImportIngressOwnershipIdentity {

  private final KafkaNamespaceProperties kafka;
  private final StorageBackendProperties backendProperties;
  private final FilesystemStorageProperties filesystem;

  /** 与既有扫描协调范围使用相同字段、排序和编码，确保升级后身份不变。 */
  public String storageId(S3StorageProperties properties, ObjectMapper objectMapper) {
    String namespace = kafka.getNamespace().trim();
    String backend = backendProperties.getBackend().trim().toLowerCase(Locale.ROOT);
    String storage = "filesystem".equals(backend)
        ? Path.of(Objects.requireNonNullElse(filesystem.getRoot(), "."))
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
