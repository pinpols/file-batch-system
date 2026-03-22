package com.example.batch.testing;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared integration-test base for modules that need real PostgreSQL, Kafka, and MinIO.
 *
 * <p>Subclasses should only declare Spring test configuration and test methods.
 * Do not duplicate container setup in child classes.
 */
@BatchIntegrationTest
public abstract class AbstractIntegrationTest {

    private static final String POSTGRES_IMAGE = "postgres:16";
    /** {@link KafkaContainer} only supports apache/kafka (not Confluent); see Testcontainers Kafka module docs. */
    private static final String KAFKA_IMAGE = "apache/kafka:4.1.2";
    private static final String DEFAULT_DB_USER = "batch_user";
    private static final String DEFAULT_DB_PASSWORD = "batch_pass_123";

    @Container
    private static final PostgreSQLContainer<?> PLATFORM_POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE))
            .withDatabaseName("batch_platform")
            .withUsername(DEFAULT_DB_USER)
            .withPassword(DEFAULT_DB_PASSWORD)
            .withInitScript("db/platform-init.sql");

    @Container
    private static final PostgreSQLContainer<?> BUSINESS_POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE))
            .withDatabaseName("batch_business")
            .withUsername(DEFAULT_DB_USER)
            .withPassword(DEFAULT_DB_PASSWORD)
            .withInitScript("db/business-init.sql");

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse(KAFKA_IMAGE));

    @Container
    private static final MinIOContainer MINIO = new MinIOContainer();

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
