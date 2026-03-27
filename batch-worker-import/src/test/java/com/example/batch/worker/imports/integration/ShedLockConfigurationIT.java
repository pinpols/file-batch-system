package com.example.batch.worker.imports.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.worker.imports.config.PlatformDataSourceConfiguration;
import com.example.batch.worker.imports.config.ShedLockConfiguration;
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
@SpringBootTest(classes = {PlatformDataSourceConfiguration.class, ShedLockConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ShedLockConfigurationIT extends AbstractIntegrationTest {

    @Autowired
    DataSource dataSource;

    @Autowired
    LockProvider lockProvider;

    @Test
    void shouldCreateShedLockTableAndConfigureJdbcTemplateLockProvider() {
        Integer tableCount = new JdbcTemplate(dataSource).queryForObject(
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
