package io.github.pinpols.batch.testing;

/** Testcontainers image tags used by integration and e2e tests. */
public final class TestContainerImages {

  /** 测试容器使用 UTF-8 locale；数据库排序规则保持镜像默认值。 */
  public static final String UTF8_LOCALE = "C.UTF-8";

  /** Keep in sync with .env.example POSTGRES_IMAGE_TAG and docker-compose.yml. */
  public static final String POSTGRES = "postgres:17.11";

  /** Keep in sync with .env.example KAFKA_IMAGE_TAG and docker-compose.yml. */
  public static final String KAFKA = "apache/kafka:4.1.2";

  /** Keep in sync with .env.example VALKEY_IMAGE_TAG and docker-compose.yml. */
  public static final String VALKEY = "valkey/valkey:8.1.10";

  /** Keep in sync with .env.example MINIO_IMAGE_REPOSITORY / MINIO_IMAGE_TAG. */
  public static final String MINIO = "bitnamilegacy/minio:2025.7.23-debian-12-r1";

  private TestContainerImages() {}
}
