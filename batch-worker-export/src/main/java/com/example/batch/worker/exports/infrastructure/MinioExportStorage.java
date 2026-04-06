package com.example.batch.worker.exports.infrastructure;

import com.example.batch.common.config.MinioStorageProperties;
import com.example.batch.common.constants.BatchFileConstants;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import com.example.batch.common.utils.MinioBucketSupport;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class MinioExportStorage {

    // byte[] 上传的最大允许尺寸（10 MB）；超过此阈值应使用 writeObject(Path, ...) 流式上传
    private static final int MAX_BYTE_UPLOAD_SIZE = 10 * 1024 * 1024;

    private final MinioStorageProperties properties;
    private final MinioClient minioClient;

    public String writeJson(String objectName, String jsonContent) {
        return writeObject(objectName, jsonContent.getBytes(StandardCharsets.UTF_8), BatchFileConstants.CONTENT_TYPE_JSON);
    }

    public String writeObject(String objectName, byte[] content, String contentType) {
        if (content.length > MAX_BYTE_UPLOAD_SIZE) {
            throw new IllegalArgumentException(
                    "content too large for byte[] upload (%d bytes); use writeObject(Path, ...) instead"
                            .formatted(content.length));
        }
        ensureBucketOrThrow();
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
        ensureBucketOrThrow();
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
        ensureBucketOrThrow();
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
        ensureBucketOrThrow();
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
        ensureBucketOrThrow();
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
        if (!ensureBucket()) {
            return false;
        }
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

    private boolean ensureBucket() {
        return MinioBucketSupport.ensureBucket(minioClient, properties.getBucket(), log, "export storage");
    }

    private void ensureBucketOrThrow() {
        if (!ensureBucket()) {
            throw new IllegalStateException("minio bucket unavailable: " + properties.getBucket());
        }
    }
}
