package com.example.batch.e2e.config;

import com.example.batch.common.config.ShedLockProviderFactory;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT2M")
public class E2eShedLockConfiguration {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return ShedLockProviderFactory.jdbcTemplateLockProvider(dataSource);
    }

    @Bean
    public LockingTaskExecutor lockingTaskExecutor(LockProvider lockProvider) {
        return new DefaultLockingTaskExecutor(lockProvider);
    }
}
