package io.github.pinpols.batch.testing;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** PostgreSQL 测试容器统一工厂，集中维护镜像、凭据和 JDBC 默认参数。 */
public final class TestPostgresContainers {

  public static final String PLATFORM_DATABASE = "batch_platform";
  public static final String BUSINESS_DATABASE = "batch_business";

  private static final String DEFAULT_DATABASE = "batch_test";
  private static final String DEFAULT_USERNAME = "batch_user";
  private static final String DEFAULT_PASSWORD = "batch_pass_123";

  private TestPostgresContainers() {}

  public static PostgreSQLContainer create() {
    return create(DEFAULT_DATABASE);
  }

  public static PostgreSQLContainer create(String databaseName) {
    return new PostgreSQLContainer(DockerImageName.parse(TestContainerImages.POSTGRES))
        .withDatabaseName(databaseName)
        .withUsername(DEFAULT_USERNAME)
        .withPassword(DEFAULT_PASSWORD)
        .withUrlParam("sslmode", "disable");
  }

  public static PostgreSQLContainer platform() {
    return create(PLATFORM_DATABASE);
  }

  public static PostgreSQLContainer business() {
    return create(BUSINESS_DATABASE);
  }
}
