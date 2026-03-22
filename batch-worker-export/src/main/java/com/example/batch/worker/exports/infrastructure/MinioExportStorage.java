package com.example.batch.worker.exports.infrastructure;

import com.example.batch.worker.exports.config.MinioStorageProperties;
import com.example.batch.common.constants.BatchFileConstants;
import io.minio.BucketExistsArgs;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MinioExportStorage {

    private final MinioStorageProperties properties;
    private MinioClient minioClient;

    @PostConstruct
    void initialize() {
        this.minioClient = MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }

    public String writeJson(String objectName, String jsonContent) {
        return writeObject(objectName, jsonContent.getBytes(StandardCharsets.UTF_8), BatchFileConstants.CONTENT_TYPE_JSON);
    }

    public String writeObject(String objectName, byte[] content, String contentType) {
        ensureBucket();
        String targetObjectName = objectName;
        if (targetObjectName == null || targetObjectName.isBlank()) {
            targetObjectName = BatchFileConstants.EXPORT_OBJECT_PREFIX + UUID.randomUUID() + BatchFileConstants.BIN_SUFFIX;
        }
        try {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(properties.getBucket())
                            .object(targetObjectName)
                            .stream(new ByteArrayInputStream(content), content.length, -1)
                            .contentType(contentType)
                            .build()
            );
            return targetObjectName;
        } catch (Exception ex) {
            throw new IllegalStateException("failed to write export object", ex);
        }
    }

    public String writeObject(String objectName, Path contentPath, String contentType) {
        ensureBucket();
        if (contentPath == null || !Files.exists(contentPath)) {
            throw new IllegalArgumentException("contentPath is required");
        }
        String targetObjectName = objectName;
        if (targetObjectName == null || targetObjectName.isBlank()) {
            targetObjectName = BatchFileConstants.EXPORT_OBJECT_PREFIX + UUID.randomUUID() + BatchFileConstants.BIN_SUFFIX;
        }
        try (InputStream inputStream = Files.newInputStream(contentPath)) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(properties.getBucket())
                            .object(targetObjectName)
                            .stream(inputStream, Files.size(contentPath), -1)
                            .contentType(contentType)
                            .build()
            );
            return targetObjectName;
        } catch (Exception ex) {
            throw new IllegalStateException("failed to write export object", ex);
        }
    }

    public void copyObject(String sourceObjectName, String targetObjectName) {
        ensureBucket();
        try {
            minioClient.copyObject(
                    CopyObjectArgs.builder()
                            .bucket(properties.getBucket())
                            .object(targetObjectName)
                            .source(CopySource.builder()
                                    .bucket(properties.getBucket())
                                    .object(sourceObjectName)
                                    .build())
                            .build()
            );
        } catch (Exception ex) {
            throw new IllegalStateException("failed to copy export object", ex);
        }
    }

    public void removeObject(String objectName) {
        ensureBucket();
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(properties.getBucket())
                            .object(objectName)
                            .build()
            );
        } catch (Exception ex) {
            throw new IllegalStateException("failed to remove export object", ex);
        }
    }

    private static String digestToHex(byte[] digest) {
        return HexFormat.of().formatHex(digest);
    }

    /**
     * SHA-256 of object bytes in the bucket (for verifying .part before promote, and final after copy).
     */
    public String sha256Hex(String objectName) {
        ensureBucket();
        if (objectName == null || objectName.isBlank()) {
            throw new IllegalArgumentException("objectName is required");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream inputStream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(properties.getBucket())
                            .object(objectName)
                            .build()
            )) {
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

    public boolean objectExists(String objectName) {
        ensureBucket();
        if (objectName == null || objectName.isBlank()) {
            return false;
        }
        try {
            minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(properties.getBucket())
                            .object(objectName)
                            .build()
            );
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private void ensureBucket() {
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(properties.getBucket()).build()
            );
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(properties.getBucket()).build());
            }
        } catch (Exception ex) {
            throw new IllegalStateException("failed to ensure bucket", ex);
        }
    }
}
