package com.example.batch.testing;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * 集成测试使用的轻量 MinIO 测试容器封装。
 */
public final class MinIOContainer extends GenericContainer<MinIOContainer> {

    private static final int MINIO_API_PORT = 9000;
    private static final int MINIO_CONSOLE_PORT = 9001;
    private static final DockerImageName IMAGE = DockerImageName.parse("minio/minio:RELEASE.2025-04-03T14-56-28Z");

    private final String accessKey;
    private final String secretKey;
    private final String defaultBucket;

    public MinIOContainer() {
        this("minioadmin", "minioadmin123", "batch-test");
    }

    public MinIOContainer(String accessKey, String secretKey, String defaultBucket) {
        super(IMAGE);
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.defaultBucket = defaultBucket;
        withExposedPorts(MINIO_API_PORT, MINIO_CONSOLE_PORT);
        withEnv("MINIO_ROOT_USER", accessKey);
        withEnv("MINIO_ROOT_PASSWORD", secretKey);
        withCommand("server", "/data", "--console-address", ":9001");
        waitingFor(Wait.forHttp("/minio/health/ready")
                .forPort(MINIO_API_PORT)
                .forStatusCode(200)
                .withStartupTimeout(Duration.ofMinutes(2)));
    }

    @Override
    public void start() {
        super.start();
        ensureBucketExists(defaultBucket);
    }

    public String getEndpoint() {
        return "http://" + getHost() + ":" + getMappedPort(MINIO_API_PORT);
    }

    public String getAccessKey() {
        return accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public String getDefaultBucket() {
        return defaultBucket;
    }

    public MinioClient client() {
        return MinioClient.builder()
                .endpoint(getEndpoint())
                .credentials(accessKey, secretKey)
                .build();
    }

    public void ensureBucketExists(String bucketName) {
        try {
            MinioClient client = client();
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
            }
        } catch (Exception exception) {
            throw new IllegalStateException("failed to ensure MinIO bucket: " + bucketName, exception);
        }
    }
}
