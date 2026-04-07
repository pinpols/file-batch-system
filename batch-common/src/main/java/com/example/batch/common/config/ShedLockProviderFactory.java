package com.example.batch.common.config;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 创建基于 JDBC 的 ShedLock 提供者的共享工具类。
 */
public final class ShedLockProviderFactory {

    private static final String SHEDLOCK_TABLE = "batch.shedlock";
    private static final String CREATE_BATCH_SCHEMA_SQL = "CREATE SCHEMA IF NOT EXISTS batch";
    private static final String CREATE_SHEDLOCK_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS batch.shedlock (
                name        VARCHAR(64)  NOT NULL PRIMARY KEY,
                lock_until  TIMESTAMPTZ  NOT NULL,
                locked_at   TIMESTAMPTZ  NOT NULL,
                locked_by   VARCHAR(255) NOT NULL
            )
            """;

    private ShedLockProviderFactory() {
    }

    public static LockProvider jdbcTemplateLockProvider(DataSource dataSource) {
        return jdbcTemplateLockProvider(dataSource, false);
    }

    public static LockProvider jdbcTemplateLockProvider(DataSource dataSource, boolean autoCreateTable) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        if (autoCreateTable) {
            ensureShedLockTable(jdbcTemplate);
        }
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(jdbcTemplate)
                        .withTableName(SHEDLOCK_TABLE)
                        .usingDbTime()
                        .build()
        );
    }

    private static void ensureShedLockTable(JdbcTemplate jdbcTemplate) {
        try {
            jdbcTemplate.execute(CREATE_BATCH_SCHEMA_SQL);
            jdbcTemplate.execute(CREATE_SHEDLOCK_TABLE_SQL);
        } catch (DataAccessException ex) {
            throw new IllegalStateException("Failed to auto-create batch.shedlock for ShedLock startup", ex);
        }
    }
}
