package io.github.pinpols.batch.testing;

/** 集成测试和 E2E 测试使用的 Testcontainers 镜像标签。 */
public final class TestContainerImages {

  /** 测试容器使用 UTF-8 locale；数据库排序规则保持镜像默认值。 */
  public static final String UTF8_LOCALE = "C.UTF-8";

  /** 应与 .env.example 中的 POSTGRES_IMAGE_TAG 及 docker-compose.yml 保持一致。 */
  public static final String POSTGRES = "postgres:17.11";

  /** 应与 .env.example 中的 KAFKA_IMAGE_TAG 及 docker-compose.yml 保持一致。 */
  public static final String KAFKA = "apache/kafka:4.1.2";

  /** 应与 .env.example 中的 VALKEY_IMAGE_TAG 及 docker-compose.yml 保持一致。 */
  public static final String VALKEY = "valkey/valkey:8.1.10";

  /** 应与 .env.example 中的 MINIO_IMAGE_REPOSITORY 和 MINIO_IMAGE_TAG 保持一致。 */
  public static final String MINIO = "bitnamilegacy/minio:2025.7.23-debian-12-r1";

  private TestContainerImages() {}
}
