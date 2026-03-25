package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * SQL consistency gate: Flyway chain on an empty PostgreSQL, plus core unique constraints and a
 * representative {@code ON CONFLICT} path.
 *
 * <p>Note: {@code docs/sql/system-test/platform_seed.sql} contains PL/pgSQL blocks and cannot be
 * replayed reliably via Spring's semicolon-based script splitter; Flyway migrations are the
 * authoritative DDL/DML baseline this test guards.
 */
@Tag("integration")
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class SqlConsistencyIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
            .withDatabaseName("batch_sql_guard")
            .withUsername("batch_user")
            .withPassword("batch_pass_123");

    @Test
    void flywayMigrationsKeyConstraintsAndUpsertProbe() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("batch", "quartz")
                .defaultSchema("batch")
                .locations("classpath:db/migration-integration")
                .load()
                .migrate();

        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
        try {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            assertUniquePresent(jdbc, "job_instance", "uk_job_instance_tenant_dedup");
            assertUniquePresent(jdbc, "outbox_event", "uk_outbox_event_key");
            assertUniquePresent(jdbc, "job_definition", "uk_job_definition_tenant_code");

            runRuntimeParameterUpsertProbe(dataSource);
        } finally {
            dataSource.destroy();
        }
    }

    private static void assertUniquePresent(JdbcTemplate jdbc, String table, String constraintName) {
        Long cnt = jdbc.queryForObject(
                """
                        select count(*) from information_schema.table_constraints tc
                        where tc.table_schema = 'batch'
                          and tc.table_name = ?
                          and tc.constraint_name = ?
                          and tc.constraint_type in ('UNIQUE', 'PRIMARY KEY')
                        """,
                Long.class,
                table,
                constraintName);
        assertThat(cnt).isEqualTo(1L);
    }

    private static void runRuntimeParameterUpsertProbe(DataSource dataSource) throws Exception {
        try (Connection c = dataSource.getConnection(); var st = c.createStatement()) {
            st.execute(
                    """
                            insert into batch.batch_runtime_default_parameter (
                                module, parameter_key, default_value, value_type, yaml_path, description
                            ) values (
                                'SQL_CONSISTENCY_IT', 'guard_probe', 'true', 'BOOLEAN', 'n/a',
                                'SqlConsistencyIT idempotent probe'
                            )
                            on conflict (module, parameter_key) do nothing
                            """);
            st.execute(
                    """
                            insert into batch.batch_runtime_default_parameter (
                                module, parameter_key, default_value, value_type, yaml_path, description
                            ) values (
                                'SQL_CONSISTENCY_IT', 'guard_probe', 'true', 'BOOLEAN', 'n/a',
                                'SqlConsistencyIT idempotent probe'
                            )
                            on conflict (module, parameter_key) do nothing
                            """);
        }
    }
}
