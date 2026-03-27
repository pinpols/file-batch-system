package com.example.batch.common.config;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Shared helper for creating the JDBC-based ShedLock provider.
 */
public final class ShedLockProviderFactory {

    private ShedLockProviderFactory() {
    }

    public static LockProvider jdbcTemplateLockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .withTableName("batch.shedlock")
                        .usingDbTime()
                        .build()
        );
    }
}
