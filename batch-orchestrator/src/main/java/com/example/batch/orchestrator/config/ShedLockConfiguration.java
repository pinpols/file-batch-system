package com.example.batch.orchestrator.config;

import com.example.batch.common.config.ShedLockProviderFactory;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ShedLock configuration for orchestrator cluster.
 *
 * <p>Ensures that each {@code @Scheduled} task runs on exactly one orchestrator instance
 * at a time, regardless of how many replicas are deployed.
 *
 * <p>Locks are stored in {@code batch.shedlock}. In normal runtime the table comes from the
 * shared batch-common Flyway migration; in local profile we allow a small auto-create fallback so
 * a fresh developer database can still boot.
 *
 * <p>{@code defaultLockAtMostFor} is a safety net: if a JVM crashes while holding a lock, the
 * lock auto-expires after this duration so another instance can take over.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT2M")
public class ShedLockConfiguration {

    @Bean
    public LockProvider lockProvider(
            DataSource dataSource,
            @Value("${batch.shedlock.auto-create:false}") boolean autoCreateTable
    ) {
        return ShedLockProviderFactory.jdbcTemplateLockProvider(dataSource, autoCreateTable);
    }
}
