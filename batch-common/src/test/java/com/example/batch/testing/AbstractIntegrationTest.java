package com.example.batch.testing;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 集成测试基类：需要真实 PostgreSQL、Kafka、MinIO 的模块继承本类。
 *
 * <p>平台库 Testcontainers 仅执行 {@code db/platform-init.sql}（与 Flyway V1 等价的 schema 边界）；
 * 表结构由各模块测试中的 Flyway 从 {@code classpath:db/migration} 完整迁移。
 *
 * <p>子类只声明 Spring 测试配置与用例方法；不要在子类中重复容器启动逻辑。
 */
@BatchIntegrationTest
public abstract class AbstractIntegrationTest {

    private static final String POSTGRES_IMAGE = "postgres:16";
    /** {@link KafkaContainer} 仅支持 apache/kafka 镜像（非 Confluent）；详见 Testcontainers Kafka 文档。 */
    private static final String KAFKA_IMAGE = "apache/kafka:4.1.2";
    private static final String DEFAULT_DB_USER = "batch_user";
    private static final String DEFAULT_DB_PASSWORD = "batch_pass_123";

    private static final PostgreSQLContainer<?> PLATFORM_POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE))
            .withDatabaseName("batch_platform")
            .withUsername(DEFAULT_DB_USER)
            .withPassword(DEFAULT_DB_PASSWORD)
            .withUrlParam("sslmode", "disable")
            .withInitScript("db/platform-init.sql");

    private static final PostgreSQLContainer<?> BUSINESS_POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE))
            .withDatabaseName("batch_business")
            .withUsername(DEFAULT_DB_USER)
            .withPassword(DEFAULT_DB_PASSWORD)
            .withUrlParam("sslmode", "disable")
            .withInitScript("db/business-init.sql");

    private static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse(KAFKA_IMAGE));

    private static final MinIOContainer MINIO = new MinIOContainer();

    static {
        // Keep test infrastructure ports stable across all integration test classes in one JVM.
        PLATFORM_POSTGRES.start();
        BUSINESS_POSTGRES.start();
        KAFKA.start();
        MINIO.start();
    }

    protected AbstractIntegrationTest() {
    }

    @DynamicPropertySource
    static void registerDynamicProperties(DynamicPropertyRegistry registry) {
        IntegrationTestInfrastructure.registerDynamicProperties(
                registry,
                PLATFORM_POSTGRES,
                BUSINESS_POSTGRES,
                KAFKA,
                MINIO
        );
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
}
