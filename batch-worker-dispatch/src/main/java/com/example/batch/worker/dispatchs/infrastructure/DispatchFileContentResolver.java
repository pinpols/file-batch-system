package com.example.batch.worker.dispatchs.infrastructure;

import com.example.batch.common.config.S3StorageProperties;
import com.example.batch.common.service.BatchObjectCryptoService;
import com.example.batch.common.storage.BatchObjectStore;
import com.example.batch.common.utils.Texts;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** 解析分发文件字节：支持本地路径或对象存储（MinIO/S3 兼容）。 */
@Component
@RequiredArgsConstructor
public class DispatchFileContentResolver {

  private final S3StorageProperties minioProperties;
  private final BatchObjectCryptoService cryptoService;
  // 复用中心对象存储(底层 client 带超时 + 连接池);ObjectProvider 惰性取,未配 MinIO 时保持 null(同历史行为)。
  private final ObjectProvider<BatchObjectStore> objectStoreProvider;
  private BatchObjectStore objectStore;

  @PostConstruct
  void init() {
    // 对象存储 bean 仅在 MinIO 配置有效时装配;未配则 null
    // (LOCAL 路径无需对象存储;远程路径在 openInputStream 里按 null 抛"MinIO not configured")。
    this.objectStore = objectStoreProvider.getIfAvailable();
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
    if (objectStore == null) {
      throw new IllegalStateException("MinIO not configured for remote storage");
    }
    String bucket =
        String.valueOf(fileRecord.getOrDefault("storage_bucket", minioProperties.getBucket()));
    if (!Texts.hasText(bucket)) {
      bucket = minioProperties.getBucket();
    }
    // M-7: 若 decryptIfNeeded 抛异常，确保对象存储流被关闭
    InputStream inputStream = objectStore.get(bucket, storagePath);
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
}
