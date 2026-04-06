package com.example.batch.testing;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Central place for integration-test property wiring.
 *
 * <p>Keep container-specific details out of child test classes.
 */
final class IntegrationTestInfrastructure {

    private static final String DEFAULT_KMS_KEY_REF = "DEFAULT_TEST";
    private static final String DEFAULT_KMS_KEY_MATERIAL = "AAAAAAAAAAAAAAAAAAAAAA==";

    private IntegrationTestInfrastructure() {
    }

    static void registerDynamicProperties(
            DynamicPropertyRegistry registry,
            PostgreSQLContainer<?> platformPostgres,
            PostgreSQLContainer<?> businessPostgres,
            KafkaContainer kafka,
            MinIOContainer minio,
            GenericContainer<?> redis
    ) {
        registerPlatformDatabaseProperties(registry, platformPostgres);
        registerKafkaProperties(registry, kafka);
        registerBusinessDatabaseProperties(registry, businessPostgres);
        registerMinioProperties(registry, minio);
        registerRedisProperties(registry, redis);
        registerSecurityProperties(registry);
    }

    static void registerPlatformDatabaseProperties(DynamicPropertyRegistry registry, PostgreSQLContainer<?> postgres) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.default-schema", () -> "batch");
        registry.add("spring.flyway.schemas", () -> "batch,quartz");
    }

    static void registerKafkaProperties(DynamicPropertyRegistry registry, KafkaContainer kafka) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    static void registerBusinessDatabaseProperties(DynamicPropertyRegistry registry, PostgreSQLContainer<?> postgres) {
        registry.add("batch.datasource.business.url", postgres::getJdbcUrl);
        registry.add("batch.datasource.business.username", postgres::getUsername);
        registry.add("batch.datasource.business.password", postgres::getPassword);
        registry.add("batch.datasource.business.schema", () -> "biz");
    }

    static void registerMinioProperties(DynamicPropertyRegistry registry, MinIOContainer minio) {
        registry.add("batch.storage.minio.endpoint", minio::getEndpoint);
        registry.add("batch.storage.minio.access-key", minio::getAccessKey);
        registry.add("batch.storage.minio.secret-key", minio::getSecretKey);
        registry.add("batch.storage.minio.bucket", minio::getDefaultBucket);
    }

    static void registerRedisProperties(DynamicPropertyRegistry registry, GenericContainer<?> redis) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    static void registerSecurityProperties(DynamicPropertyRegistry registry) {
        registry.add("batch.security.testing-open", () -> true);
        registry.add("batch.security.kms.default-key-ref", () -> DEFAULT_KMS_KEY_REF);
        registry.add("batch.security.kms.keys." + DEFAULT_KMS_KEY_REF, () -> DEFAULT_KMS_KEY_MATERIAL);
    }
}
