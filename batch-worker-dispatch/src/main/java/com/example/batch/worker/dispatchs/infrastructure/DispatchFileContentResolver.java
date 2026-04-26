package com.example.batch.worker.dispatchs.infrastructure;

import com.example.batch.common.config.MinioStorageProperties;
import com.example.batch.common.service.BatchObjectCryptoService;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.common.utils.Texts;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 解析分发文件字节：支持本地路径或对象存储（MinIO/S3 兼容）。 */
@Component
@RequiredArgsConstructor
public class DispatchFileContentResolver {

  private final MinioStorageProperties minioProperties;
  private final BatchObjectCryptoService cryptoService;
  private MinioClient minioClient;

  @PostConstruct
  void init() {
    if (Texts.hasText(minioProperties.getEndpoint())
        && Texts.hasText(minioProperties.getAccessKey())
        && Texts.hasText(minioProperties.getSecretKey())) {
      this.minioClient =
          MinioClient.builder()
              .endpoint(minioProperties.getEndpoint())
              .credentials(minioProperties.getAccessKey(), minioProperties.getSecretKey())
              .build();
    }
  }

  /** 打开文件内容流（调用方负责关闭）；大文件建议使用 {@link #streamToConsumer}。 */
  public InputStream openInputStream(Map<String, Object> fileRecord) throws Exception {
    String storageType =
        String.valueOf(fileRecord.getOrDefault("storage_type", "")).toUpperCase(Locale.ROOT);
    String storagePath = String.valueOf(fileRecord.getOrDefault("storage_path", ""));
    if (!Texts.hasText(storagePath)) {
      throw new IllegalStateException("storage_path missing");
    }
    // C-4: 规范化前拒绝路径遍历序列
    if (storagePath.contains("..")) {
      throw new SecurityException("storage_path contains path traversal sequence: " + storagePath);
    }
    Path local = Path.of(storagePath).toAbsolutePath().normalize();
    if ("LOCAL".equals(storageType) || Files.isRegularFile(local)) {
      return Files.newInputStream(local);
    }
    if (minioClient == null) {
      throw new IllegalStateException("MinIO not configured for remote storage");
    }
    String bucket =
        String.valueOf(fileRecord.getOrDefault("storage_bucket", minioProperties.getBucket()));
    if (!Texts.hasText(bucket)) {
      bucket = minioProperties.getBucket();
    }
    // M-7: 若 decryptIfNeeded 抛异常，确保 MinIO 流被关闭
    InputStream inputStream =
        minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(storagePath).build());
    try {
      if (cryptoService.isBypassMode()) {
        return inputStream;
      }
      return cryptoService.decryptIfNeeded(inputStream);
    } catch (Exception e) {
      try {
        inputStream.close();
      } catch (Exception ignored) {
      }
      throw e;
    }
  }

  private Map<String, Object> fileSecurity(Map<String, Object> fileRecord) {
    Object metadata = fileRecord.get("metadata_json");
    if (metadata instanceof Map<?, ?> map) {
      Map<String, Object> security = new LinkedHashMap<>();
      map.forEach((k, v) -> security.put(String.valueOf(k), v));
      return security;
    }
    String text = metadata == null ? null : String.valueOf(metadata);
    if (Texts.hasText(text)) {
      try {
        Object parsed = JsonUtils.fromJson(text, Map.class);
        if (parsed instanceof Map<?, ?> map) {
          Map<String, Object> security = new LinkedHashMap<>();
          map.forEach((k, v) -> security.put(String.valueOf(k), v));
          return security;
        }
      } catch (Exception ignored) {
        return Map.of();
      }
    }
    return Map.of();
  }
}
