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

/**
 * 导出文件对象存储操作封装，提供写入、复制、删除及 SHA-256 校验等能力。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MinioExportStorage {

    // byte[] 上传的最大允许大小（10 MB）；超过此阈值应使用 writeObject(Path, ...) 流式上传
    private static final int MAX_BYTE_UPLOAD_SIZE = 10 * 1024 * 1024;

    private final MinioStorageProperties properties;
    private final MinioClient minioClient;

    /**
     * 将 JSON 字符串写入对象存储。
     *
     * @param objectName  目标对象路径
     * @param jsonContent JSON 内容字符串
     * @return 实际写入的对象路径
     */
    public String writeJson(String objectName, String jsonContent) {
        return writeObject(objectName, jsonContent.getBytes(StandardCharsets.UTF_8), BatchFileConstants.CONTENT_TYPE_JSON);
    }

    /**
     * 将字节数组写入对象存储，内容大小不得超过 {@code MAX_BYTE_UPLOAD_SIZE}。
     *
     * @param objectName  目标对象路径，为空时自动生成
     * @param content     文件字节内容
     * @param contentType MIME 类型
     * @return 实际写入的对象路径
     */
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

    /**
     * 以流式方式将本地文件写入对象存储，适用于大文件场景。
     *
     * @param objectName  目标对象路径，为空时自动生成
     * @param contentPath 本地文件路径
     * @param contentType MIME 类型
     * @return 实际写入的对象路径
     */
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

    /**
     * 在同一 bucket 内复制对象。
     *
     * @param sourceObjectName 源对象路径
     * @param targetObjectName 目标对象路径
     */
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

    /**
     * 从 bucket 中删除指定对象。
     *
     * @param objectName 要删除的对象路径
     */
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
     * 计算 bucket 中指定对象的 SHA-256 十六进制摘要（用于上传前后的完整性校验）。
     *
     * @param objectName 对象路径
     * @return SHA-256 十六进制字符串
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

    /**
     * 检查指定对象是否存在于 bucket 中。
     *
     * @param objectName 对象路径
     * @return 存在返回 {@code true}，否则返回 {@code false}
     */
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
