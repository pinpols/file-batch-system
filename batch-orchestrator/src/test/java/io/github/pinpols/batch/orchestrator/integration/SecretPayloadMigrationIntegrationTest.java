package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.config.BatchKmsProperties;
import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.service.BatchObjectCryptoService;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.startup.SecretPayloadFlywayCallback;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 验证 V209→V210→V211 逐级升级会加密历史载荷，并在应用就绪前收紧最终约束。 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class SecretPayloadMigrationIntegrationTest {

  private static final String LEGACY_PAYLOAD =
      "{\"format\":\"PLAINTEXT\",\"token\":\"legacy-plain-token\"}";
  private static final String V210_NULL_CHECK_PAYLOAD =
      "{\"token\":\"v210-null-check-plain-token\"}";

  @Container
  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES =
      TestPostgresContainers.create("secret_payload_migration");

  @Test
  void upgradeEncryptsLegacyPayloadAcrossV210AndV211BeforeValidatingConstraint() {
    SecretPayloadFlywayCallback callback =
        new SecretPayloadFlywayCallback(secretPayloadProtector());
    flyway(callback).target("209").load().migrate();
    JdbcTemplate jdbc = jdbcTemplate();
    jdbc.update("""
        insert into batch.secret_version(
          tenant_id, secret_ref, secret_name, version_no, secret_status,
          current_version, secret_payload
        ) values (?, ?, ?, 1, 'DRAFT', false, ?::jsonb)
        """, "tenant-a", "legacy-ref", "Legacy Secret", LEGACY_PAYLOAD);

    flyway(callback).target("210").load().migrate();
    jdbc.update("""
        insert into batch.secret_version(
          tenant_id, secret_ref, secret_name, version_no, secret_status,
          current_version, secret_payload
        ) values (?, ?, ?, 1, 'DRAFT', false, ?::jsonb)
        """, "tenant-a", "v210-ref", "V210 Legacy Secret", V210_NULL_CHECK_PAYLOAD);

    flyway(callback).load().migrate();

    String protectedV209Payload = secretPayload(jdbc, "legacy-ref");
    assertThat(protectedV209Payload)
        .contains(SecretPayloadProtector.FORMAT)
        .doesNotContain("legacy-plain-token");
    String protectedV210Payload = secretPayload(jdbc, "v210-ref");
    assertThat(protectedV210Payload)
        .contains(SecretPayloadProtector.FORMAT)
        .doesNotContain("v210-null-check-plain-token");
    assertThat(constraintValidated(jdbc)).isTrue();
    assertThatThrownBy(() -> jdbc.update("""
            insert into batch.secret_version(
              tenant_id, secret_ref, secret_name, version_no, secret_status,
              current_version, secret_payload
            ) values ('tenant-a', 'plain-ref', 'Plain Secret', 1, 'DRAFT', false, ?::jsonb)
            """, LEGACY_PAYLOAD))
        .hasMessageContaining("ck_secret_version_payload_protected");
    assertThatThrownBy(() -> jdbc.update("""
            insert into batch.secret_version(
              tenant_id, secret_ref, secret_name, version_no, secret_status,
              current_version, secret_payload
            ) values ('tenant-a', 'missing-fields-ref', 'Missing Fields', 1, 'DRAFT', false, ?::jsonb)
            """, V210_NULL_CHECK_PAYLOAD))
        .hasMessageContaining("ck_secret_version_payload_protected");
  }

  private static FluentConfiguration flyway(SecretPayloadFlywayCallback callback) {
    return Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .schemas("batch", "quartz")
        .defaultSchema("batch")
        .locations("classpath:db/migration")
        .callbacks(callback)
        .configuration(Map.of("flyway.postgresql.transactional.lock", "false"));
  }

  private static JdbcTemplate jdbcTemplate() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    return new JdbcTemplate(dataSource);
  }

  private static SecretPayloadProtector secretPayloadProtector() {
    BatchSecurityProperties securityProperties = new BatchSecurityProperties();
    securityProperties.setBypassMode(false);
    BatchKmsProperties kmsProperties = new BatchKmsProperties();
    kmsProperties.setDefaultKeyRef("migration-test");
    kmsProperties.setKeys(Map.of(
        "migration-test",
        Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8))));
    return new SecretPayloadProtector(
        new BatchObjectCryptoService(securityProperties, kmsProperties));
  }

  private static String secretPayload(JdbcTemplate jdbc, String secretRef) {
    return jdbc.queryForObject(
        "select secret_payload::text from batch.secret_version where secret_ref = ?",
        String.class,
        secretRef);
  }

  private static boolean constraintValidated(JdbcTemplate jdbc) {
    return Boolean.TRUE.equals(jdbc.queryForObject("""
        select convalidated
          from pg_constraint
         where conname = 'ck_secret_version_payload_protected'
           and conrelid = 'batch.secret_version'::regclass
        """, Boolean.class));
  }
}
