package com.example.batch.orchestrator.infrastructure.file;

import com.example.batch.common.config.MinioStorageProperties;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.http.Method;
import io.minio.messages.Item;
import com.example.batch.common.utils.MinioBucketSupport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MinioGovernanceStorage {

    private final MinioStorageProperties properties;

    /**
     * 治理任务只做对象清点和清理，不在这里承载业务编排。
     */
    public List<StorageObjectView> listObjects(String prefix, int limit, boolean includeTemporaryObjects) {
        if (!ensureBucket()) {
            return List.of();
        }
        List<StorageObjectView> objects = new ArrayList<>();
        try {
            Iterable<Result<Item>> results = client().listObjects(
                    ListObjectsArgs.builder()
                            .bucket(properties.getBucket())
                            .prefix(prefix == null ? "" : prefix)
                            .recursive(true)
                            .build()
            );
            for (Result<Item> result : results) {
                if (objects.size() >= limit) {
                    break;
                }
                Item item = result.get();
                String objectName = item.objectName();
                if (!includeTemporaryObjects && (objectName.endsWith(".part") || objectName.startsWith("tmp/"))) {
                    continue;
                }
                objects.add(new StorageObjectView(
                        properties.getBucket(),
                        objectName,
                        item.size(),
                        item.etag(),
                        item.lastModified() == null ? null : item.lastModified().toInstant()
                ));
            }
            return objects;
        } catch (Exception exception) {
            throw new IllegalStateException("failed to list minio objects", exception);
        }
    }

    public void removeObject(String objectName) {
        if (!ensureBucket()) {
            throw new IllegalStateException("minio bucket unavailable: " + properties.getBucket());
        }
        try {
            client().removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(properties.getBucket())
                            .object(objectName)
                            .build()
            );
        } catch (Exception exception) {
            throw new IllegalStateException("failed to remove object: " + objectName, exception);
        }
    }

    public String createPresignedDownloadUrl(String bucket, String objectName, int expirySeconds) {
        try {
            String targetBucket = bucket == null || bucket.isBlank() ? properties.getBucket() : bucket;
            if (!ensureBucket(targetBucket)) {
                throw new IllegalStateException("minio bucket unavailable: " + targetBucket);
            }
            return client().getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(targetBucket)
                            .object(objectName)
                            .expiry(Math.max(60, expirySeconds))
                            .build()
            );
        } catch (Exception exception) {
            throw new IllegalStateException("failed to create presigned url for object: " + objectName, exception);
        }
    }

    private boolean ensureBucket() {
        return ensureBucket(properties.getBucket());
    }

    private boolean ensureBucket(String bucket) {
        return MinioBucketSupport.ensureBucket(client(), bucket, log, "orchestrator governance");
    }

    private MinioClient client() {
        return MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }

    public record StorageObjectView(
            String bucket,
            String objectName,
            long size,
            String etag,
            Instant lastModified
    ) {
    }
}
