package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 校验与 {@code application-local.yml} 相同的 Flyway 路径（{@code classpath:db/migration}，源为 {@code docs/sql/flyway}）。
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class LocalFlywayPlatformMigrationsIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
            .withDatabaseName("batch_platform")
            .withUsername("batch_user")
            .withPassword("batch_pass_123");

    @Test
    void migrationPlatformCreatesBatchDayInstance() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("batch", "quartz")
                .defaultSchema("batch")
                .locations("classpath:db/migration")
                .load()
                .migrate();

        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
        try {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            Long cnt = jdbc.queryForObject(
                    """
                            select count(*) from information_schema.tables
                            where table_schema = 'batch' and table_name = 'batch_day_instance'
                            """,
                    Long.class);
            assertThat(cnt).isEqualTo(1L);
        } finally {
            dataSource.destroy();
        }
    }
}
