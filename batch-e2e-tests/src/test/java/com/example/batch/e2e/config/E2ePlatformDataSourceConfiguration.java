package com.example.batch.e2e.config;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * When worker modules register extra {@link DataSource} beans, JDBC auto-config may not register
 * the usual primary {@code dataSource} for {@code spring.datasource}. Orchestrator and platform
 * MyBatis need that bean.
 */
@Configuration
public class E2ePlatformDataSourceConfiguration {

    @Bean(name = "dataSource")
    @Primary
    @ConditionalOnMissingBean(name = "dataSource")
    public DataSource platformDataSource(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password) {
        return DataSourceBuilder.create()
                .url(url)
                .username(username)
                .password(password)
                .driverClassName("org.postgresql.Driver")
                .build();
    }
}
