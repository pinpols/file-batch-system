package com.example.batch.worker.dispatchs.config;

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

    @Value("${batch.shedlock.auto-create:false}")
    private boolean autoCreateTable;

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return ShedLockProviderFactory.jdbcTemplateLockProvider(dataSource, autoCreateTable);
    }
}
