package com.example.batch.worker.dispatchs.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.worker.dispatchs.config.ShedLockConfiguration;
import javax.sql.DataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration smoke: ensures ShedLock wiring survives Flyway and test init scripts.
 */
@SpringBootTest(classes = {
        ShedLockConfigurationIT.DispatchTestDataSourceConfiguration.class,
        ShedLockConfiguration.class
},
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

    @Configuration
    @EnableConfigurationProperties(DataSourceProperties.class)
    static class DispatchTestDataSourceConfiguration {

        @Bean
        @Primary
        DataSource dispatchPlatformDataSource(DataSourceProperties properties) {
            HikariConfig hikariConfig = new HikariConfig();
            hikariConfig.setJdbcUrl(properties.determineUrl());
            hikariConfig.setUsername(properties.determineUsername());
            hikariConfig.setPassword(properties.determinePassword());
            String driverClassName = properties.determineDriverClassName();
            if (hikariConfig.getDriverClassName() == null || hikariConfig.getDriverClassName().isBlank()) {
                hikariConfig.setDriverClassName(driverClassName);
            }
            return new HikariDataSource(hikariConfig);
        }
    }
}
