package io.github.pinpols.batch.testing;

/** Testcontainers image tags used by integration and e2e tests. */
public final class TestContainerImages {

  /** Keep in sync with .env.example POSTGRES_IMAGE_TAG and docker-compose.yml. */
  public static final String POSTGRES = "postgres:17";

  /** Keep in sync with .env.example KAFKA_IMAGE_TAG and docker-compose.yml. */
  public static final String KAFKA = "apache/kafka:4.1.2";

  /** Keep in sync with .env.example VALKEY_IMAGE_TAG and docker-compose.yml. */
  public static final String VALKEY = "valkey/valkey:8.1";

  /** Keep in sync with .env.example MINIO_IMAGE_TAG and docker-compose.yml. */
  public static final String MINIO = "minio/minio:RELEASE.2025-09-07T16-13-09Z";

  private TestContainerImages() {}
}
