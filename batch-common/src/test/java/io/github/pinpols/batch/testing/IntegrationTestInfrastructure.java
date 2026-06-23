package io.github.pinpols.batch.testing;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * 集成测试属性注册的统一入口。
 *
 * <p>将容器相关的配置细节与子测试类隔离。
 */
final class IntegrationTestInfrastructure {

  private static final String DEFAULT_KMS_KEY_REF = "DEFAULT_TEST";
  private static final String DEFAULT_KMS_KEY_MATERIAL = "AAAAAAAAAAAAAAAAAAAAAA==";

  private IntegrationTestInfrastructure() {}

  static void registerDynamicProperties(
      DynamicPropertyRegistry registry,
      PostgreSQLContainer<?> platformPostgres,
      PostgreSQLContainer<?> businessPostgres,
      KafkaContainer kafka,
      ObjectStoreContainer objectStore,
      GenericContainer<?> redis) {
    registerPlatformDatabaseProperties(registry, platformPostgres);
    registerKafkaProperties(registry, kafka);
    registerBusinessDatabaseProperties(registry, businessPostgres);
    registerObjectStoreProperties(registry, objectStore);
    registerRedisProperties(registry, redis);
    registerSecurityProperties(registry);
  }

  static void registerPlatformDatabaseProperties(
      DynamicPropertyRegistry registry, PostgreSQLContainer<?> postgres) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
    // 单 JVM 多 Spring 上下文复用同一 PG 时,Hikari 默认 10 连接 × N 上下文易触达 PG 默认 ~100 上限 (too many clients)。
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> "20");
    registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
    registry.add("spring.flyway.enabled", () -> true);
    registry.add("spring.flyway.default-schema", () -> "batch");
    registry.add("spring.flyway.schemas", () -> "batch,quartz");
  }

  static void registerKafkaProperties(DynamicPropertyRegistry registry, KafkaContainer kafka) {
    registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
  }

  static void registerBusinessDatabaseProperties(
      DynamicPropertyRegistry registry, PostgreSQLContainer<?> postgres) {
    registry.add("batch.datasource.business.url", postgres::getJdbcUrl);
    registry.add("batch.datasource.business.username", postgres::getUsername);
    registry.add("batch.datasource.business.password", postgres::getPassword);
    registry.add("batch.datasource.business.schema", () -> "biz");
  }

  static void registerObjectStoreProperties(
      DynamicPropertyRegistry registry, ObjectStoreContainer objectStore) {
    registry.add("batch.storage.s3.endpoint", objectStore::getEndpoint);
    registry.add("batch.storage.s3.access-key", objectStore::getAccessKey);
    registry.add("batch.storage.s3.secret-key", objectStore::getSecretKey);
    registry.add("batch.storage.s3.bucket", objectStore::getDefaultBucket);
  }

  static void registerRedisProperties(DynamicPropertyRegistry registry, GenericContainer<?> redis) {
    registry.add("spring.data.redis.host", redis::getHost);
    registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
  }

  static void registerSecurityProperties(DynamicPropertyRegistry registry) {
    registry.add("batch.security.bypass-mode", () -> true);
    registry.add("batch.security.kms.default-key-ref", () -> DEFAULT_KMS_KEY_REF);
    registry.add("batch.security.kms.keys." + DEFAULT_KMS_KEY_REF, () -> DEFAULT_KMS_KEY_MATERIAL);
    // 单 JVM 长耗时 IT：覆盖 application-test.yml merge 漂移，禁用会与 claim / launch 并发竞态的后台 Bean
    registry.add("batch.worker.drain.heartbeat-timeout-scheduler-enabled", () -> "false");
    registry.add("batch.partition-lease.reclaim-scheduler-enabled", () -> "false");
  }
}
