package com.example.batch.worker.exports.infrastructure;

import com.example.batch.worker.exports.config.MinioStorageProperties;
import io.minio.BucketExistsArgs;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class MinioExportStorage {

    private final MinioClient minioClient;
    private final MinioStorageProperties properties;

    public MinioExportStorage(MinioStorageProperties properties) {
        this.properties = properties;
        this.minioClient = MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }

    public String writeJson(String objectName, String jsonContent) {
        return writeObject(objectName, jsonContent.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    public String writeObject(String objectName, byte[] content, String contentType) {
        ensureBucket();
        String targetObjectName = objectName;
        if (targetObjectName == null || targetObjectName.isBlank()) {
            targetObjectName = "exports/" + UUID.randomUUID() + ".bin";
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
