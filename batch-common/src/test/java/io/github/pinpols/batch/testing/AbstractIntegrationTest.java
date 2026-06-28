package io.github.pinpols.batch.testing;

import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 集成测试基类：需要真实 PostgreSQL、Kafka、MinIO、Redis 的模块继承本类。
 *
 * <p>平台库 Testcontainers 仅执行 {@code db/platform-init.sql}（与 Flyway V1 等价的 schema 边界）； 表结构由各模块测试中的
 * Flyway 从 {@code classpath:db/migration} 完整迁移。
 *
 * <p>子类只声明 Spring 测试配置与用例方法；不要在子类中重复容器启动逻辑。
 */
@BatchIntegrationTest
public abstract class AbstractIntegrationTest {

  private static final String DEFAULT_DB_USER = "batch_user";
  private static final String DEFAULT_DB_PASSWORD = "batch_pass_123";

  // 2026-05 IT 提速:MinIO + Redis 加 .withReuse(true) 跨 JVM 复用。
  // **PG 不加 reuse**:reuse 会让 outbox_event 等表跨 run 残留,
  // 破坏 MultiTenantConcurrent / OutboxForwarderRetry / ImportFailure 等依赖 outbox 状态的 IT。
  // PG 单次启动 ~3-5s,影响有限,稳妥优先。
  @SuppressWarnings("resource")
  private static final PostgreSQLContainer PLATFORM_POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse(TestContainerImages.POSTGRES))
          .withDatabaseName("batch_platform")
          .withUsername(DEFAULT_DB_USER)
          .withPassword(DEFAULT_DB_PASSWORD)
          .withUrlParam("sslmode", "disable")
          .withInitScript("db/platform-init.sql")
          .withCommand("postgres", "-c", "max_connections=500");

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer BUSINESS_POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse(TestContainerImages.POSTGRES))
          .withDatabaseName("batch_business")
          .withUsername(DEFAULT_DB_USER)
          .withPassword(DEFAULT_DB_PASSWORD)
          .withUrlParam("sslmode", "disable")
          .withInitScript("db/business-init.sql")
          .withCommand("postgres", "-c", "max_connections=500");

  // Kafka 不加 withReuse:OutboxPublishCircuitBreakerKafkaFailureIT 等用 stopKafka/startKafka 做
  // fault injection,reuse 容器禁止 stop。Kafka 单次启动 ~5s,影响有限。
  private static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse(TestContainerImages.KAFKA));

  @SuppressWarnings("resource")
  private static final ObjectStoreContainer MINIO = new ObjectStoreContainer().withReuse(true);

  @SuppressWarnings("resource")
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse(TestContainerImages.VALKEY))
          .withExposedPorts(6379)
          .withCommand("redis-server", "--appendonly", "yes")
          .withReuse(true);

  static {
    // 在同一 JVM 中所有集成测试类之间保持测试基础设施端口稳定。
    PLATFORM_POSTGRES.start();
    BUSINESS_POSTGRES.start();
    KAFKA.start();
    MINIO.start();
    REDIS.start();
  }

  protected AbstractIntegrationTest() {}

  /**
   * 供子类显式调用的 outbox 表 truncate 辅助方法。
   *
   * <p>**不放 @BeforeEach 全局回退**:实测 ImportFailureE2eIT 等测试在测试方法内依赖 outbox event scheduler 投递时间窗,全局
   * truncate 会清掉 application 启动时已写入的 测试 fixture / 异步事件 → 测试 timeout。
   *
   * <p>只让确实需要「上轮残留必清」的测试(如 MultiTenantConcurrentE2eIT 的租户隔离断言) 显式在 @BeforeEach 调本方法。
   */
  protected static void truncateOutboxTables(DataSource dataSource) {
    String sql =
        "TRUNCATE TABLE batch.outbox_event, "
            + "batch.event_outbox_retry, "
            + "batch.trigger_outbox_event, "
            + "batch.worker_report_outbox "
            + "CASCADE";
    try (Connection c = dataSource.getConnection();
        Statement s = c.createStatement()) {
      s.execute(sql);
    } catch (Exception ignored) {
      // 容忍:表不存在(模块没跑到 outbox migration)
    }
  }

  @DynamicPropertySource
  static void registerDynamicProperties(DynamicPropertyRegistry registry) {
    IntegrationTestInfrastructure.registerDynamicProperties(
        registry, PLATFORM_POSTGRES, BUSINESS_POSTGRES, KAFKA, MINIO, REDIS);
  }

  protected static String platformJdbcUrl() {
    return PLATFORM_POSTGRES.getJdbcUrl();
  }

  protected static String businessJdbcUrl() {
    return BUSINESS_POSTGRES.getJdbcUrl();
  }

  protected static String kafkaBootstrapServers() {
    return KAFKA.getBootstrapServers();
  }

  protected static void stopKafkaForFaultInjection() {
    KAFKA.stop();
  }

  protected static void startKafkaAfterFaultInjection() {
    if (!KAFKA.isRunning()) {
      KAFKA.start();
    }
  }

  protected static String s3Endpoint() {
    return MINIO.getEndpoint();
  }

  protected static String s3Bucket() {
    return MINIO.getDefaultBucket();
  }

  protected static void ensureS3Bucket(String bucketName) {
    MINIO.ensureBucketExists(bucketName);
  }

  protected static String redisHost() {
    return REDIS.getHost();
  }

  protected static int redisPort() {
    return REDIS.getMappedPort(6379);
  }
}
