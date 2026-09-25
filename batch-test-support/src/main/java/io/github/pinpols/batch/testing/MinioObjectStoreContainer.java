package io.github.pinpols.batch.testing;

import java.time.Duration;
import lombok.Getter;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/** 集成测试使用的 MinIO 测试容器封装；通用入口请使用 {@link TestObjectStoreEndpoint}。 */
public final class MinioObjectStoreContainer extends GenericContainer<MinioObjectStoreContainer>
    implements TestObjectStoreEndpoint {

  private static final int MINIO_API_PORT = 9000;
  private static final int MINIO_CONSOLE_PORT = 9001;
  private static final DockerImageName IMAGE = DockerImageName.parse(TestContainerImages.MINIO);

  @Getter
  private final String accessKey;

  @Getter
  private final String secretKey;

  @Getter
  private final String defaultBucket;

  public MinioObjectStoreContainer() {
    this("minioadmin", "minioadmin123", "batch-test");
  }

  public MinioObjectStoreContainer(String accessKey, String secretKey, String defaultBucket) {
    super(IMAGE);
    this.accessKey = accessKey;
    this.secretKey = secretKey;
    this.defaultBucket = defaultBucket;
    withExposedPorts(MINIO_API_PORT, MINIO_CONSOLE_PORT);
    withEnv("MINIO_ROOT_USER", accessKey);
    withEnv("MINIO_ROOT_PASSWORD", secretKey);
    withCommand("server", "/data", "--console-address", ":9001");
    waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));
  }

  @Override
  public void start() {
    super.start();
    ensureBucketExists(defaultBucket);
  }

  public String getEndpoint() {
    return "http://" + getHost() + ":" + getMappedPort(MINIO_API_PORT);
  }

  @Override
  public final boolean equals(Object other) {
    return super.equals(other);
  }

  @Override
  public final int hashCode() {
    return super.hashCode();
  }
}
