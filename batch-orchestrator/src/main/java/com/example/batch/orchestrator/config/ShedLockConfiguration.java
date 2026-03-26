package com.example.batch.orchestrator.config;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ShedLock configuration for orchestrator cluster.
 *
 * <p>Ensures that each {@code @Scheduled} task runs on exactly one orchestrator instance
 * at a time, regardless of how many replicas are deployed.
 *
 * <p>Locks are stored in {@code batch.shedlock} (created by V29 Flyway migration).
 * {@code defaultLockAtMostFor} is a safety net: if a JVM crashes while holding a lock,
 * the lock auto-expires after this duration so another instance can take over.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT2M")
public class ShedLockConfiguration {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .withTableName("batch.shedlock")
                        .usingDbTime()   // use DB clock, avoids clock-skew issues between nodes
                        .build()
        );
    }
}
