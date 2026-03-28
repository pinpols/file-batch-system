package com.example.batch.worker.exports.config;

import com.example.batch.common.config.ShedLockProviderFactory;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
