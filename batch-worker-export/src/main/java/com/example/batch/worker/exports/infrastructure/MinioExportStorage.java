package com.example.batch.worker.exports.infrastructure;

import com.example.batch.common.config.S3StorageProperties;
import com.example.batch.common.constants.BatchFileConstants;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.storage.BatchObjectStore;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 导出文件对象存储操作封装，提供写入、复制、删除及 SHA-256 校验等能力。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MinioExportStorage {

  // byte[] 上传的最大允许大小（10 MB）；超过此阈值应使用 writeObject(Path, ...) 流式上传
  private static final int MAX_BYTE_UPLOAD_SIZE = 10 * 1024 * 1024;

  private final S3StorageProperties properties;
  private final BatchObjectStore objectStore;

  /**
   * 将 JSON 字符串写入对象存储。
   *
   * @param objectName 目标对象路径
   * @param jsonContent JSON 内容字符串
   * @return 实际写入的对象路径
   */
  public String writeJson(String objectName, String jsonContent) {
    return writeObject(
        objectName,
        jsonContent.getBytes(StandardCharsets.UTF_8),
        BatchFileConstants.CONTENT_TYPE_JSON);
  }

  /**
   * 将字节数组写入对象存储，内容大小不得超过 {@code MAX_BYTE_UPLOAD_SIZE}。
   *
   * @param objectName 目标对象路径，为空时自动生成
   * @param content 文件字节内容
   * @param contentType MIME 类型
   * @return 实际写入的对象路径
   */
  public String writeObject(String objectName, byte[] content, String contentType) {
    if (content.length > MAX_BYTE_UPLOAD_SIZE) {
      throw new IllegalArgumentException(
          "content too large for byte[] upload (%d bytes); use writeObject(Path, ...) instead"
              .formatted(content.length));
    }
    String targetObjectName = objectName;
    if (targetObjectName == null || targetObjectName.isBlank()) {
      targetObjectName =
          BatchFileConstants.EXPORT_OBJECT_PREFIX
              + UUID.randomUUID()
              + BatchFileConstants.BIN_SUFFIX;
    }
    try {
      objectStore.put(
          properties.getBucket(),
          targetObjectName,
          new ByteArrayInputStream(content),
          content.length,
          contentType);
      return targetObjectName;
    } catch (Exception ex) {
      throw new IllegalStateException("failed to write export object", ex);
    }
  }

  /**
   * 以流式方式将本地文件写入对象存储，适用于大文件场景。
   *
   * @param objectName 目标对象路径，为空时自动生成
   * @param contentPath 本地文件路径
   * @param contentType MIME 类型
   * @return 实际写入的对象路径
   */
  public String writeObject(String objectName, Path contentPath, String contentType) {
    if (contentPath == null || !Files.exists(contentPath)) {
      throw new IllegalArgumentException("contentPath is required");
    }
    String targetObjectName = objectName;
    if (targetObjectName == null || targetObjectName.isBlank()) {
      targetObjectName =
          BatchFileConstants.EXPORT_OBJECT_PREFIX
              + UUID.randomUUID()
              + BatchFileConstants.BIN_SUFFIX;
    }
    try (InputStream inputStream = Files.newInputStream(contentPath)) {
      objectStore.put(
          properties.getBucket(),
          targetObjectName,
          inputStream,
          Files.size(contentPath),
          contentType);
      return targetObjectName;
    } catch (Exception ex) {
      throw new IllegalStateException("failed to write export object", ex);
    }
  }

  /**
   * 在同一 bucket 内复制对象。
   *
   * @param sourceObjectName 源对象路径
   * @param targetObjectName 目标对象路径
   */
  public void copyObject(String sourceObjectName, String targetObjectName) {
    try {
      objectStore.copy(properties.getBucket(), sourceObjectName, targetObjectName);
    } catch (Exception ex) {
      throw new IllegalStateException("failed to copy export object", ex);
    }
  }

  /**
   * 从 bucket 中删除指定对象。
   *
   * @param objectName 要删除的对象路径
   */
  public void removeObject(String objectName) {
    try {
      objectStore.delete(properties.getBucket(), objectName);
    } catch (Exception ex) {
      throw new IllegalStateException("failed to remove export object", ex);
    }
  }

  private static String digestToHex(byte[] digest) {
    return HexFormat.of().formatHex(digest);
  }

  /**
   * 计算 bucket 中指定对象的 SHA-256 十六进制摘要（用于上传前后的完整性校验）。
   *
   * @param objectName 对象路径
   * @return SHA-256 十六进制字符串
   */
  public String sha256Hex(String objectName) {
    if (objectName == null || objectName.isBlank()) {
      throw new IllegalArgumentException("objectName is required");
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream inputStream = objectStore.get(properties.getBucket(), objectName)) {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) >= 0) {
          if (read > 0) {
            digest.update(buffer, 0, read);
          }
        }
      }
      return digestToHex(digest.digest());
    } catch (Exception ex) {
      throw new IllegalStateException("failed to checksum export object", ex);
    }
  }

  /**
   * 检查指定对象是否存在于 bucket 中。
   *
   * @param objectName 对象路径
   * @return 存在返回 {@code true}，否则返回 {@code false}
   */
  public boolean objectExists(String objectName) {
    if (objectName == null || objectName.isBlank()) {
      return false;
    }
    try {
      return objectStore.exists(properties.getBucket(), objectName);
    } catch (Exception ex) {
      SwallowedExceptionLogger.warn(MinioExportStorage.class, "catch:Exception", ex);

      return false;
    }
  }
}
