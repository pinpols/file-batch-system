package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.testing.AbstractIntegrationTest;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration smoke: ensures ShedLock wiring survives Flyway and test init scripts.
 */
@SpringBootTest(classes = BatchOrchestratorApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ShedLockConfigurationIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    LockProvider lockProvider;

    @Autowired
    DataSource dataSource;

    @Test
    void shouldCreateShedLockTableAndConfigureJdbcTemplateLockProvider() {
        Integer tableCount = jdbcTemplate.queryForObject(
                """
                select count(*)
                from information_schema.tables
                where table_schema = 'batch'
                  and table_name = 'shedlock'
                """,
                Integer.class
        );

        assertThat(tableCount).isEqualTo(1);
        assertThat(lockProvider).isInstanceOf(JdbcTemplateLockProvider.class);
        assertThat(dataSource).isNotNull();
    }
}
