package com.example.batch.worker.dispatchs.infrastructure;

import com.example.batch.common.service.BatchObjectCryptoService;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.worker.dispatchs.config.MinioStorageProperties;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Resolves file bytes for dispatch: local path or object storage (MinIO/S3-compatible).
 */
@Component
@RequiredArgsConstructor
public class DispatchFileContentResolver {

    private final MinioStorageProperties minioProperties;
    private final BatchObjectCryptoService cryptoService;
    private MinioClient minioClient;

    @PostConstruct
    void init() {
        if (StringUtils.hasText(minioProperties.getEndpoint())
                && StringUtils.hasText(minioProperties.getAccessKey())
                && StringUtils.hasText(minioProperties.getSecretKey())) {
            this.minioClient = MinioClient.builder()
                    .endpoint(minioProperties.getEndpoint())
                    .credentials(minioProperties.getAccessKey(), minioProperties.getSecretKey())
                    .build();
        }
    }

    /**
     * Opens stream for file content (caller must close). Prefer {@link #streamToConsumer} for large files.
     */
    public InputStream openInputStream(Map<String, Object> fileRecord) throws Exception {
        String storageType = String.valueOf(fileRecord.getOrDefault("storage_type", "")).toUpperCase(Locale.ROOT);
        String storagePath = String.valueOf(fileRecord.getOrDefault("storage_path", ""));
        if (!StringUtils.hasText(storagePath)) {
            throw new IllegalStateException("storage_path missing");
        }
        Path local = Path.of(storagePath);
        if ("LOCAL".equals(storageType) || Files.isRegularFile(local)) {
            return Files.newInputStream(local);
        }
        if (minioClient == null) {
            throw new IllegalStateException("MinIO not configured for remote storage");
        }
        String bucket = String.valueOf(fileRecord.getOrDefault("storage_bucket", minioProperties.getBucket()));
        if (!StringUtils.hasText(bucket)) {
            bucket = minioProperties.getBucket();
        }
        InputStream inputStream = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(storagePath).build()
        );
        if (cryptoService.isTestingOpen()) {
            return inputStream;
        }
        return cryptoService.decryptIfNeeded(inputStream);
    }

    private Map<String, Object> fileSecurity(Map<String, Object> fileRecord) {
        Object metadata = fileRecord.get("metadata_json");
        if (metadata instanceof Map<?, ?> map) {
            Map<String, Object> security = new java.util.LinkedHashMap<>();
            map.forEach((k, v) -> security.put(String.valueOf(k), v));
            return security;
        }
        String text = metadata == null ? null : String.valueOf(metadata);
        if (StringUtils.hasText(text)) {
            try {
                Object parsed = JsonUtils.fromJson(text, Map.class);
                if (parsed instanceof Map<?, ?> map) {
                    Map<String, Object> security = new java.util.LinkedHashMap<>();
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
