package com.example.batch.orchestrator.infrastructure.file;

import com.example.batch.orchestrator.config.MinioStorageProperties;
import io.minio.BucketExistsArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MinioGovernanceStorage {

    private final MinioStorageProperties properties;

    /**
     * 治理任务只做对象清点和清理，不在这里承载业务编排。
     */
    public List<StorageObjectView> listObjects(String prefix, int limit, boolean includeTemporaryObjects) {
        ensureBucket();
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
        ensureBucket();
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

    private void ensureBucket() {
        try {
            boolean exists = client().bucketExists(BucketExistsArgs.builder().bucket(properties.getBucket()).build());
            if (!exists) {
                client().makeBucket(MakeBucketArgs.builder().bucket(properties.getBucket()).build());
            }
        } catch (Exception exception) {
            throw new IllegalStateException("failed to ensure minio bucket", exception);
        }
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
