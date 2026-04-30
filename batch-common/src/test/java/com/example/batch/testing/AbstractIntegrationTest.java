package com.example.batch.testing;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
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

  // 版本需与 .env.example POSTGRES_IMAGE_TAG 保持一致
  private static final String POSTGRES_IMAGE = "postgres:16";
  // 版本需与 .env.example KAFKA_IMAGE_TAG 保持一致；KafkaContainer 仅支持 apache/kafka 镜像（非 Confluent）
  private static final String KAFKA_IMAGE = "apache/kafka:4.1.2";
  // 版本需与 .env.example REDIS_IMAGE_TAG 保持一致
  private static final String REDIS_IMAGE = "redis:7.4";
  // MinIO 版本在 MinIOContainer 中维护，需与 .env.example MINIO_IMAGE_TAG 保持一致

  private static final String DEFAULT_DB_USER = "batch_user";
  private static final String DEFAULT_DB_PASSWORD = "batch_pass_123";

  private static final PostgreSQLContainer<?> PLATFORM_POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE))
          .withDatabaseName("batch_platform")
          .withUsername(DEFAULT_DB_USER)
          .withPassword(DEFAULT_DB_PASSWORD)
          .withUrlParam("sslmode", "disable")
          .withInitScript("db/platform-init.sql");

  private static final PostgreSQLContainer<?> BUSINESS_POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE))
          .withDatabaseName("batch_business")
          .withUsername(DEFAULT_DB_USER)
          .withPassword(DEFAULT_DB_PASSWORD)
          .withUrlParam("sslmode", "disable")
          .withInitScript("db/business-init.sql");

  private static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse(KAFKA_IMAGE));

  private static final MinIOContainer MINIO = new MinIOContainer();

  @SuppressWarnings("resource")
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
          .withExposedPorts(6379)
          .withCommand("redis-server", "--appendonly", "yes");

  static {
    // 在同一 JVM 中所有集成测试类之间保持测试基础设施端口稳定。
    PLATFORM_POSTGRES.start();
    BUSINESS_POSTGRES.start();
    KAFKA.start();
    MINIO.start();
    REDIS.start();
  }

  protected AbstractIntegrationTest() {}

  @DynamicPropertySource
  static void registerDynamicProperties(DynamicPropertyRegistry registry) {
    IntegrationTestInfrastructure.registerDynamicProperties(
        registry, PLATFORM_POSTGRES, BUSINESS_POSTGRES, KAFKA, MINIO, REDIS);
    // 注意:不要在此处全局覆盖 batch.trigger.async-launch.enabled —— @DynamicPropertySource 优先级
    // 高于子类 @SpringBootTest(properties=),会把"显式想测异步路径"的 IT(如 TriggerAsyncLaunchFullChainE2eIT)
    // 强制拉回同步桥,导致 KafkaListener 不实例化、test 超时。需要同步路径的 IT 在自己的 @SpringBootTest
    // properties 里显式声明 enabled=false (见 TriggerService/TriggerDedupRequiresNew/QuartzLaunchJob/
    // ConcurrentTaskClaim 等)。
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

  protected static String minioEndpoint() {
    return MINIO.getEndpoint();
  }

  protected static String minioBucket() {
    return MINIO.getDefaultBucket();
  }

  protected static void ensureMinioBucket(String bucketName) {
    MINIO.ensureBucketExists(bucketName);
  }

  protected static String redisHost() {
    return REDIS.getHost();
  }

  protected static int redisPort() {
    return REDIS.getMappedPort(6379);
  }
}
